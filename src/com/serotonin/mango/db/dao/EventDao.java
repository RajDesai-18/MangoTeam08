/*
    Mango - Open Source M2M - http://mango.serotoninsoftware.com
    Copyright (C) 2006-2011 Serotonin Software Technologies Inc.
    @author Matthew Lohbihler
    
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.serotonin.mango.db.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;

import com.serotonin.ShouldNeverHappenException;
import com.serotonin.db.spring.ExtendedJdbcTemplate;
import com.serotonin.db.spring.GenericRowMapper;
import com.serotonin.db.spring.GenericTransactionCallback;
import com.serotonin.mango.Common;
import com.serotonin.mango.db.dao.EventDao.EventHandlerRowMapper;
import com.serotonin.mango.db.dao.EventDao.EventInstanceRowMapper;
import com.serotonin.mango.db.dao.EventDao.PendingEventCacheEntry;
import com.serotonin.mango.db.dao.EventDao.UserEventInstanceRowMapper;
import com.serotonin.mango.db.dao.EventDao.UserPendingEventRetriever;
import com.serotonin.mango.rt.event.EventInstance;
import com.serotonin.mango.rt.event.type.AuditEventType;
import com.serotonin.mango.rt.event.type.CompoundDetectorEventType;
import com.serotonin.mango.rt.event.type.DataPointEventType;
import com.serotonin.mango.rt.event.type.DataSourceEventType;
import com.serotonin.mango.rt.event.type.EventType;
import com.serotonin.mango.rt.event.type.MaintenanceEventType;
import com.serotonin.mango.rt.event.type.PublisherEventType;
import com.serotonin.mango.rt.event.type.ScheduledEventType;
import com.serotonin.mango.rt.event.type.SystemEventType;
import com.serotonin.mango.vo.UserComment;
import com.serotonin.mango.vo.event.EventHandlerVO;
import com.serotonin.mango.vo.event.EventTypeVO;
import com.serotonin.mango.web.dwr.EventsDwr;
import com.serotonin.util.SerializationHelper;
import com.serotonin.util.StringUtils;
import com.serotonin.web.i18n.LocalizableMessage;
import com.serotonin.web.i18n.LocalizableMessageParseException;

public class EventDao extends BaseDao {
    private static final int MAX_PENDING_EVENTS = 100;

    public void saveEvent(EventInstance event) {
        if (event.getId() == Common.NEW_ID)
            insertEvent(event);
        else
            updateEvent(event);
    }

    private static final String EVENT_INSERT = "insert into events (typeId, typeRef1, typeRef2, activeTs, rtnApplicable, rtnTs, rtnCause, "
            + "  alarmLevel, message, ackTs) " + "values (?,?,?,?,?,?,?,?,?,?)";
    private static final int[] EVENT_INSERT_TYPES = { Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.BIGINT,
            Types.CHAR, Types.BIGINT, Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.BIGINT };

    private void insertEvent(EventInstance event) {
        EventType type = event.getEventType();

        Object[] args = new Object[10];
        args[0] = type.getEventSourceId();
        args[1] = type.getReferenceId1();
        args[2] = type.getReferenceId2();
        args[3] = event.getActiveTimestamp();
        args[4] = boolToChar(event.isRtnApplicable());
        if (!event.isActive()) {
            args[5] = event.getRtnTimestamp();
            args[6] = event.getRtnCause();
        }
        args[7] = event.getAlarmLevel();
        args[8] = event.getMessage().serialize();
        if (!event.isAlarm()) {
            event.setAcknowledgedTimestamp(event.getActiveTimestamp());
            args[9] = event.getAcknowledgedTimestamp();
        }
        event.setId(doInsert(EVENT_INSERT, args, EVENT_INSERT_TYPES));
        event.setEventComments(new LinkedList<UserComment>());
    }

    private static final String EVENT_UPDATE = "update events set rtnTs=?, rtnCause=? where id=?";

    private void updateEvent(EventInstance event) {
        ejt.update(EVENT_UPDATE, new Object[] { event.getRtnTimestamp(), event.getRtnCause(), event.getId() });
        updateCache(event);
    }

    private static final String EVENT_ACK = "update events set ackTs=?, ackUserId=?, alternateAckSource=? where id=? and ackTs is null";
    private static final String USER_EVENT_ACK = "update userEvents set silenced=? where eventId=?";

    public void ackEvent(int eventId, long time, int userId, int alternateAckSource) {
        // Ack the event
        ejt.update(EVENT_ACK, new Object[] { time, userId == 0 ? null : userId, alternateAckSource, eventId });
        // Silence the user events
        ejt.update(USER_EVENT_ACK, new Object[] { boolToChar(true), eventId });
        // Clear the cache
        clearCache();
    }

    private static final String USER_EVENTS_INSERT = "insert into userEvents (eventId, userId, silenced) values (?,?,?)";

    public void insertUserEvents(final int eventId, final List<Integer> userIds, final boolean alarm) {
        ejt.batchUpdate(USER_EVENTS_INSERT, new BatchPreparedStatementSetter() {
            @Override
            public int getBatchSize() {
                return userIds.size();
            }

            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setInt(1, eventId);
                ps.setInt(2, userIds.get(i));
                ps.setString(3, boolToChar(!alarm));
            }
        });

        if (alarm) {
            for (int userId : userIds)
                removeUserIdFromCache(userId);
        }
    }

    private static final String BASIC_EVENT_SELECT = "select e.id, e.typeId, e.typeRef1, e.typeRef2, e.activeTs, e.rtnApplicable, e.rtnTs, e.rtnCause, "
            + "  e.alarmLevel, e.message, e.ackTs, e.ackUserId, u.username, e.alternateAckSource "
            + "from events e "
            + "  left join users u on e.ackUserId=u.id ";

    public List<EventInstance> getActiveEvents() {
        List<EventInstance> results = query(BASIC_EVENT_SELECT + "where e.rtnApplicable=? and e.rtnTs is null",
                new Object[] { boolToChar(true) }, new EventInstanceRowMapper());
        attachRelationalInfo(results);
        return results;
    }

    private static final String EVENT_SELECT_WITH_USER_DATA = "select e.id, e.typeId, e.typeRef1, e.typeRef2, e.activeTs, e.rtnApplicable, e.rtnTs, e.rtnCause, "
            + "  e.alarmLevel, e.message, e.ackTs, e.ackUserId, u.username, e.alternateAckSource, ue.silenced "
            + "from events e "
            + "  left join users u on e.ackUserId=u.id "
            + "  left join userEvents ue on e.id=ue.eventId ";

    public List<EventInstance> getEventsForDataPoint(int dataPointId, int userId) {
        List<EventInstance> results = query(EVENT_SELECT_WITH_USER_DATA + "where e.typeId="
                + EventType.EventSources.DATA_POINT + "  and e.typeRef1=? " + "  and ue.userId=? "
                + "order by e.activeTs desc", new Object[] { dataPointId, userId }, new UserEventInstanceRowMapper());
        attachRelationalInfo(results);
        return results;
    }

    public List<EventInstance> getPendingEventsForDataPoint(int dataPointId, int userId) {
        // Check the cache
        List<EventInstance> userEvents = getFromCache(userId);
        if (userEvents == null) {
            // This is a potentially long running query, so run it offline.
            userEvents = Collections.emptyList();
            addToCache(userId, userEvents);
            Common.timer.execute(new UserPendingEventRetriever(userId));
        }

        List<EventInstance> list = null;
        for (EventInstance e : userEvents) {
            if (e.getEventType().getDataPointId() == dataPointId) {
                if (list == null)
                    list = new ArrayList<EventInstance>();
                list.add(e);
            }
        }

        if (list == null)
            return Collections.emptyList();
        return list;
    }

    class UserPendingEventRetriever implements Runnable {
        private final int userId;

        UserPendingEventRetriever(int userId) {
            this.userId = userId;
        }

        @Override
        public void run() {
            addToCache(userId, getPendingEvents(EventType.EventSources.DATA_POINT, -1, userId));
        }
    }

    public List<EventInstance> getPendingEventsForDataSource(int dataSourceId, int userId) {
        return getPendingEvents(EventType.EventSources.DATA_SOURCE, dataSourceId, userId);
    }

    public List<EventInstance> getPendingEventsForPublisher(int publisherId, int userId) {
        return getPendingEvents(EventType.EventSources.PUBLISHER, publisherId, userId);
    }

    List<EventInstance> getPendingEvents(int typeId, int typeRef1, int userId) {
        Object[] params;
        StringBuilder sb = new StringBuilder();
        sb.append(EVENT_SELECT_WITH_USER_DATA);
        sb.append("where e.typeId=?");

        if (typeRef1 == -1) {
            params = new Object[] { typeId, userId, boolToChar(true) };
        }
        else {
            sb.append("  and e.typeRef1=?");
            params = new Object[] { typeId, typeRef1, userId, boolToChar(true) };
        }
        sb.append("  and ue.userId=? ");
        sb.append("  and (e.ackTs is null or (e.rtnApplicable=? and e.rtnTs is null and e.alarmLevel > 0)) ");
        sb.append("order by e.activeTs desc");

        List<EventInstance> results = query(sb.toString(), params, new UserEventInstanceRowMapper());
        attachRelationalInfo(results);
        return results;
    }

    public List<EventInstance> getPendingEvents(int userId) {
        List<EventInstance> results = query(EVENT_SELECT_WITH_USER_DATA
                + "where ue.userId=? and e.ackTs is null order by e.activeTs desc", new Object[] { userId },
                new UserEventInstanceRowMapper(), MAX_PENDING_EVENTS);
        attachRelationalInfo(results);
        return results;
    }

    private EventInstance getEventInstance(int eventId) {
        return queryForObject(BASIC_EVENT_SELECT + "where e.id=?", new Object[] { eventId },
                new EventInstanceRowMapper());
    }

    public static class EventInstanceRowMapper implements GenericRowMapper<EventInstance> {
        public EventInstance mapRow(ResultSet rs, int rowNum) throws SQLException {
            EventType type = createEventType(rs, 2);

            LocalizableMessage message;
            try {
                message = LocalizableMessage.deserialize(rs.getString(10));
            }
            catch (LocalizableMessageParseException e) {
                message = new LocalizableMessage("common.default", rs.getString(10));
            }

            EventInstance event = new EventInstance(type, rs.getLong(5), charToBool(rs.getString(6)), rs.getInt(9),
                    message, null);
            event.setId(rs.getInt(1));
            long rtnTs = rs.getLong(7);
            if (!rs.wasNull())
                event.returnToNormal(rtnTs, rs.getInt(8));
            long ackTs = rs.getLong(11);
            if (!rs.wasNull()) {
                event.setAcknowledgedTimestamp(ackTs);
                event.setAcknowledgedByUserId(rs.getInt(12));
                if (!rs.wasNull())
                    event.setAcknowledgedByUsername(rs.getString(13));
                event.setAlternateAckSource(rs.getInt(14));
            }

            return event;
        }
    }

    class UserEventInstanceRowMapper extends EventInstanceRowMapper {
        @Override
        public EventInstance mapRow(ResultSet rs, int rowNum) throws SQLException {
            EventInstance event = super.mapRow(rs, rowNum);
            event.setSilenced(charToBool(rs.getString(15)));
            if (!rs.wasNull())
                event.setUserNotified(true);
            return event;
        }
    }

    static EventType createEventType(ResultSet rs, int offset) throws SQLException {
        int typeId = rs.getInt(offset);
        EventType type;
        if (typeId == EventType.EventSources.DATA_POINT)
            type = new DataPointEventType(rs.getInt(offset + 1), rs.getInt(offset + 2));
        else if (typeId == EventType.EventSources.DATA_SOURCE)
            type = new DataSourceEventType(rs.getInt(offset + 1), rs.getInt(offset + 2));
        else if (typeId == EventType.EventSources.SYSTEM)
            type = new SystemEventType(rs.getInt(offset + 1), rs.getInt(offset + 2));
        else if (typeId == EventType.EventSources.COMPOUND)
            type = new CompoundDetectorEventType(rs.getInt(offset + 1));
        else if (typeId == EventType.EventSources.SCHEDULED)
            type = new ScheduledEventType(rs.getInt(offset + 1));
        else if (typeId == EventType.EventSources.PUBLISHER)
            type = new PublisherEventType(rs.getInt(offset + 1), rs.getInt(offset + 2));
        else if (typeId == EventType.EventSources.AUDIT)
            type = new AuditEventType(rs.getInt(offset + 1), rs.getInt(offset + 2));
        else if (typeId == EventType.EventSources.MAINTENANCE)
            type = new MaintenanceEventType(rs.getInt(offset + 1));
        else
            throw new ShouldNeverHappenException("Unknown event type: " + typeId);
        return type;
    }

    private void attachRelationalInfo(List<EventInstance> list) {
        for (EventInstance e : list)
            attachRelationalInfo(e);
    }

    private static final String EVENT_COMMENT_SELECT = UserCommentRowMapper.USER_COMMENT_SELECT
            + "where uc.commentType= " + UserComment.TYPE_EVENT + " and uc.typeKey=? " + "order by uc.ts";

    void attachRelationalInfo(EventInstance event) {
        event.setEventComments(query(EVENT_COMMENT_SELECT, new Object[] { event.getId() }, new UserCommentRowMapper()));
    }

    public EventInstance insertEventComment(int eventId, UserComment comment) {
        new UserDao().insertUserComment(UserComment.TYPE_EVENT, eventId, comment);
        return getEventInstance(eventId);
    }

    public int purgeEventsBefore(final long time) {
        // Find a list of event ids with no remaining acknowledgements pending.
        final ExtendedJdbcTemplate ejt2 = ejt;
        int count = getTransactionTemplate().execute(new GenericTransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                int count = ejt2.update("delete from events " + "where activeTs<? " + "  and ackTs is not null "
                        + "  and (rtnApplicable=? or (rtnApplicable=? and rtnTs is not null))", new Object[] { time,
                        boolToChar(false), boolToChar(true) });

                // Delete orphaned user comments.
                ejt2.update("delete from userComments where commentType=" + UserComment.TYPE_EVENT
                        + "  and typeKey not in (select id from events)");

                return count;
            }
        });

        clearCache();

        return count;
    }

    public int getEventCount() {
        return ejt.queryForInt("select count(*) from events");
    }

//  Code Smell 3: Shotgun Surgery - (Change Preventer)
    public List<EventInstance> search(int eventId, int eventSourceType, String status, int alarmLevel,
            final String[] keywords, int userId, final ResourceBundle bundle, final int from, final int to,
            final Date date) {
    
    List<Object> params = new ArrayList<>();
    StringBuilder sql = new StringBuilder(EVENT_SELECT_WITH_USER_DATA);
    
    sql.append("where ue.userId=?");
    params.add(userId);

    addFilterCondition("e.id=?", eventId, params, sql);
    addFilterCondition("e.typeId=?", eventSourceType, params, sql);
    addStatusCondition(status, params, sql);
    addFilterCondition("e.alarmLevel=?", alarmLevel, params, sql);

    sql.append(" order by e.activeTs desc");

    return executeSearchQuery(sql.toString(), params, keywords, bundle, from, to, date);
    
    }

    private void addFilterCondition(String condition, int value, List<Object> params, StringBuilder sql) {
        if (value != 0 && value != -1) {
            sql.append(" and ").append(condition);
            params.add(value);
        }
    }
    
    private void addStatusCondition(String status, List<Object> params, StringBuilder sql) {
        if (EventsDwr.STATUS_ACTIVE.equals(status)) {
            sql.append(" and e.rtnApplicable=? and e.rtnTs is null");
            params.add(boolToChar(true));
        } else if (EventsDwr.STATUS_RTN.equals(status)) {
            sql.append(" and e.rtnApplicable=? and e.rtnTs is not null");
            params.add(boolToChar(true));
        } else if (EventsDwr.STATUS_NORTN.equals(status)) {
            sql.append(" and e.rtnApplicable=?");
            params.add(boolToChar(false));
        }
    }
    
    private List<EventInstance> executeSearchQuery(String sql, List<Object> params, final String[] keywords,
            final ResourceBundle bundle, final int from, final int to, final Date date) {

        final List<EventInstance> results = new ArrayList<>(to - from);
        final UserEventInstanceRowMapper rowMapper = new UserEventInstanceRowMapper();
        final int[] data = new int[2];

        ejt.query(sql, params.toArray(), (ResultSet rs) -> {
            int row = 0;
            long dateTs = date == null ? -1 : date.getTime();
            int startRow = -1;

            while (rs.next()) {
                EventInstance e = rowMapper.mapRow(rs, 0);
                attachRelationalInfo(e);

                boolean add = true; // Default to adding the event

                if (keywords != null) {
                    // Perform the filtering directly here
                    StringBuilder text = new StringBuilder();
                    text.append(e.getMessage().getLocalizedMessage(bundle));
                    for (UserComment comment : e.getEventComments()) {
                        text.append(' ').append(comment.getComment());
                    }

                    String[] values = text.toString().split("\\s+");

                    for (String keyword : keywords) {
                        if (keyword.startsWith("-")) {
                            if (StringUtils.globWhiteListMatchIgnoreCase(values, keyword.substring(1))) {
                                add = false;
                                break;
                            }
                        } else {
                            if (!StringUtils.globWhiteListMatchIgnoreCase(values, keyword)) {
                                add = false;
                                break;
                            }
                        }
                    }
                }

                if (add) {
                    if (date != null && e.getActiveTimestamp() <= dateTs && results.size() < to - from) {
                        if (startRow == -1) {
                            startRow = row;
                        }
                        results.add(e);
                    } else if (row >= from && row < to) {
                        results.add(e);
                    }
                    row++;
                }
            }
            data[0] = row;
            data[1] = startRow;
            return null;
        });

        searchRowCount = data[0];
        startRow = data[1];
        return results;
    }



    private int searchRowCount;
    private int startRow;

    public int getSearchRowCount() {
        return searchRowCount;
    }

    public int getStartRow() {
        return startRow;
    }

    //
    // /
    // / Event handlers
    // /
    //
    public String generateUniqueXid() {
        return generateUniqueXid(EventHandlerVO.XID_PREFIX, "eventHandlers");
    }

    public boolean isXidUnique(String xid, int excludeId) {
        return isXidUnique(xid, excludeId, "eventHandlers");
    }

    public EventType getEventHandlerType(int handlerId) {
        return queryForObject("select eventTypeId, eventTypeRef1, eventTypeRef2 from eventHandlers where id=?",
                new Object[] { handlerId }, new GenericRowMapper<EventType>() {
                    public EventType mapRow(ResultSet rs, int rowNum) throws SQLException {
                        return createEventType(rs, 1);
                    }
                });
    }

    public List<EventHandlerVO> getEventHandlers(EventType type) {
        return getEventHandlers(type.getEventSourceId(), type.getReferenceId1(), type.getReferenceId2());
    }

    public List<EventHandlerVO> getEventHandlers(EventTypeVO type) {
        return getEventHandlers(type.getTypeId(), type.getTypeRef1(), type.getTypeRef2());
    }

    public List<EventHandlerVO> getEventHandlers() {
        return query(EVENT_HANDLER_SELECT, new EventHandlerRowMapper());
    }

    /**
     * Note: eventHandlers.eventTypeRef2 matches on both the given ref2 and 0. This is to allow a single set of event
     * handlers to be defined for user login events, rather than have to individually define them for each user.
     */
    private List<EventHandlerVO> getEventHandlers(int typeId, int ref1, int ref2) {
        return query(EVENT_HANDLER_SELECT + "where eventTypeId=? and eventTypeRef1=? "
                + "  and (eventTypeRef2=? or eventTypeRef2=0)", new Object[] { typeId, ref1, ref2 },
                new EventHandlerRowMapper());
    }

    public EventHandlerVO getEventHandler(int eventHandlerId) {
        return queryForObject(EVENT_HANDLER_SELECT + "where id=?", new Object[] { eventHandlerId },
                new EventHandlerRowMapper());
    }

    public EventHandlerVO getEventHandler(String xid) {
        return queryForObject(EVENT_HANDLER_SELECT + "where xid=?", new Object[] { xid }, new EventHandlerRowMapper(),
                null);
    }

    private static final String EVENT_HANDLER_SELECT = "select id, xid, alias, data from eventHandlers ";

    class EventHandlerRowMapper implements GenericRowMapper<EventHandlerVO> {
        public EventHandlerVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            EventHandlerVO h = (EventHandlerVO) SerializationHelper.readObject(rs.getBlob(4).getBinaryStream());
            h.setId(rs.getInt(1));
            h.setXid(rs.getString(2));
            h.setAlias(rs.getString(3));
            return h;
        }
    }

    public EventHandlerVO saveEventHandler(final EventType type, final EventHandlerVO handler) {
        if (type == null)
            return saveEventHandler(0, 0, 0, handler);
        return saveEventHandler(type.getEventSourceId(), type.getReferenceId1(), type.getReferenceId2(), handler);
    }

    public EventHandlerVO saveEventHandler(final EventTypeVO type, final EventHandlerVO handler) {
        if (type == null)
            return saveEventHandler(0, 0, 0, handler);
        return saveEventHandler(type.getTypeId(), type.getTypeRef1(), type.getTypeRef2(), handler);
    }

    private EventHandlerVO saveEventHandler(final int typeId, final int typeRef1, final int typeRef2,
            final EventHandlerVO handler) {
        getTransactionTemplate().execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                if (handler.getId() == Common.NEW_ID)
                    insertEventHandler(typeId, typeRef1, typeRef2, handler);
                else
                    updateEventHandler(handler);
            }
        });
        return getEventHandler(handler.getId());
    }

    void insertEventHandler(int typeId, int typeRef1, int typeRef2, EventHandlerVO handler) {
        handler.setId(doInsert(
                "insert into eventHandlers (xid, alias, eventTypeId, eventTypeRef1, eventTypeRef2, data) values (?,?,?,?,?,?)",
                new Object[] { handler.getXid(), handler.getAlias(), typeId, typeRef1, typeRef2,
                        SerializationHelper.writeObject(handler) }, new int[] { Types.VARCHAR, Types.VARCHAR,
                        Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.BLOB }));
        AuditEventType.raiseAddedEvent(AuditEventType.TYPE_EVENT_HANDLER, handler);
    }

    void updateEventHandler(EventHandlerVO handler) {
        EventHandlerVO old = getEventHandler(handler.getId());
        ejt.update("update eventHandlers set xid=?, alias=?, data=? where id=?", new Object[] { handler.getXid(),
                handler.getAlias(), SerializationHelper.writeObject(handler), handler.getId() }, new int[] {
                Types.VARCHAR, Types.VARCHAR, Types.BLOB, Types.INTEGER });
        AuditEventType.raiseChangedEvent(AuditEventType.TYPE_EVENT_HANDLER, old, handler);
    }

    public void deleteEventHandler(final int handlerId) {
        EventHandlerVO handler = getEventHandler(handlerId);
        ejt.update("delete from eventHandlers where id=?", new Object[] { handlerId });
        AuditEventType.raiseDeletedEvent(AuditEventType.TYPE_EVENT_HANDLER, handler);
    }

    //
    // /
    // / User alarms
    // /
    //
    private static final String SILENCED_SELECT = "select ue.silenced " + "from events e "
            + "  join userEvents ue on e.id=ue.eventId " + "where e.id=? " + "  and ue.userId=? "
            + "  and e.ackTs is null";

    public boolean toggleSilence(int eventId, int userId) {
        String result = ejt.queryForObject(SILENCED_SELECT, new Object[] { eventId, userId }, String.class, null);
        if (result == null)
            return true;

        boolean silenced = !charToBool(result);
        ejt.update("update userEvents set silenced=? where eventId=? and userId=?", new Object[] {
                boolToChar(silenced), eventId, userId });
        return silenced;
    }

    public int getHighestUnsilencedAlarmLevel(int userId) {
        return ejt.queryForInt("select max(e.alarmLevel) from userEvents u " + "  join events e on u.eventId=e.id "
                + "where u.silenced=? and u.userId=?", new Object[] { boolToChar(false), userId });
    }

    //
    // /
    // / Pending event caching
    // /
    //
    static class PendingEventCacheEntry {
        private final List<EventInstance> list;
        private final long createTime;

        public PendingEventCacheEntry(List<EventInstance> list) {
            this.list = list;
            createTime = System.currentTimeMillis();
        }

        public List<EventInstance> getList() {
            return list;
        }

        public boolean hasExpired() {
            return System.currentTimeMillis() - createTime > CACHE_TTL;
        }
    }

    private static Map<Integer, PendingEventCacheEntry> pendingEventCache = new ConcurrentHashMap<Integer, PendingEventCacheEntry>();

    private static final long CACHE_TTL = 300000; // 5 minutes

    public static List<EventInstance> getFromCache(int userId) {
        PendingEventCacheEntry entry = pendingEventCache.get(userId);
        if (entry == null)
            return null;
        if (entry.hasExpired()) {
            pendingEventCache.remove(userId);
            return null;
        }
        return entry.getList();
    }

    public static void addToCache(int userId, List<EventInstance> list) {
        pendingEventCache.put(userId, new PendingEventCacheEntry(list));
    }

    public static void updateCache(EventInstance event) {
        if (event.isAlarm() && event.getEventType().getEventSourceId() == EventType.EventSources.DATA_POINT)
            pendingEventCache.clear();
    }

    public static void removeUserIdFromCache(int userId) {
        pendingEventCache.remove(userId);
    }

    public static void clearCache() {
        pendingEventCache.clear();
    }
}
