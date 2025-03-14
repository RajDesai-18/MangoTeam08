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
import java.util.List;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;

import com.serotonin.db.spring.GenericRowMapper;
import com.serotonin.mango.Common;
import com.serotonin.mango.db.dao.UserDao.UserRowMapper;
import com.serotonin.mango.rt.dataImage.SetPointSource;
import com.serotonin.mango.rt.event.EventInstance;
import com.serotonin.mango.vo.User;
import com.serotonin.mango.vo.UserComment;
import com.serotonin.mango.vo.permission.DataPointAccess;
import com.serotonin.web.taglib.Functions;

public class UserDao extends BaseDao {
    private static final String USER_SELECT = "select id, username, password, email, phone, admin, disabled, selectedWatchList, homeUrl, lastLogin, "
            + "  receiveAlarmEmails, receiveOwnAuditEvents " + "from users ";

    public User getUser(int id) {
        User user = queryForObject(USER_SELECT + "where id=?", new Object[] { id }, new UserRowMapper(), null);
        populateUserPermissions(user);
        return user;
    }

    public User getUser(String username) {
        User user = queryForObject(USER_SELECT + "where lower(username)=?", new Object[] { username.toLowerCase() },
                new UserRowMapper(), null);
        populateUserPermissions(user);
        return user;
    }

    class UserRowMapper implements GenericRowMapper<User> {
        public User mapRow(ResultSet rs, int rowNum) throws SQLException {
            User user = new User();
            int i = 0;
            user.setId(rs.getInt(++i));
            user.setUsername(rs.getString(++i));
            user.setPassword(rs.getString(++i));
            user.setEmail(rs.getString(++i));
            user.setPhone(rs.getString(++i));
            user.setAdmin(charToBool(rs.getString(++i)));
            user.setDisabled(charToBool(rs.getString(++i)));
            user.setSelectedWatchList(rs.getInt(++i));
            user.setHomeUrl(rs.getString(++i));
            user.setLastLogin(rs.getLong(++i));
            user.setReceiveAlarmEmails(rs.getInt(++i));
            user.setReceiveOwnAuditEvents(charToBool(rs.getString(++i)));
            return user;
        }
    }

    public List<User> getUsers() {
        List<User> users = query(USER_SELECT + "order by username", new Object[0], new UserRowMapper());
        populateUserPermissions(users);
        return users;
    }

    public List<User> getActiveUsers() {
        List<User> users = query(USER_SELECT + "where disabled=?", new Object[] { boolToChar(false) },
                new UserRowMapper());
        populateUserPermissions(users);
        return users;
    }

    private void populateUserPermissions(List<User> users) {
        for (User user : users)
            populateUserPermissions(user);
    }

    private static final String SELECT_DATA_SOURCE_PERMISSIONS = "select dataSourceId from dataSourceUsers where userId=?";
    private static final String SELECT_DATA_POINT_PERMISSIONS = "select dataPointId, permission from dataPointUsers where userId=?";

    public void populateUserPermissions(User user) {
        if (user == null)
            return;

        user.setDataSourcePermissions(queryForList(SELECT_DATA_SOURCE_PERMISSIONS, new Object[] { user.getId() },
                Integer.class));
        user.setDataPointPermissions(query(SELECT_DATA_POINT_PERMISSIONS, new Object[] { user.getId() },
                new GenericRowMapper<DataPointAccess>() {
                    public DataPointAccess mapRow(ResultSet rs, int rowNum) throws SQLException {
                        DataPointAccess a = new DataPointAccess();
                        a.setDataPointId(rs.getInt(1));
                        a.setPermission(rs.getInt(2));
                        return a;
                    }
                }));
    }

    public void saveUser(final User user) {
        getTransactionTemplate().execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                if (user.getId() == Common.NEW_ID)
                    insertUser(user);
                else
                    updateUser(user);
            }
        });
    }

    private static final String USER_INSERT = "insert into users ("
            + "  username, password, email, phone, admin, disabled, homeUrl, receiveAlarmEmails, receiveOwnAuditEvents) "
            + "values (?,?,?,?,?,?,?,?,?)";

    void insertUser(User user) {
        int id = doInsert(
                USER_INSERT,
                new Object[] { user.getUsername(), user.getPassword(), user.getEmail(), user.getPhone(),
                        boolToChar(user.isAdmin()), boolToChar(user.isDisabled()), user.getHomeUrl(),
                        user.getReceiveAlarmEmails(), boolToChar(user.isReceiveOwnAuditEvents()) }, new int[] {
                        Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                        Types.VARCHAR, Types.INTEGER, Types.VARCHAR });
        user.setId(id);
        saveRelationalData(user);
    }

    private static final String USER_UPDATE = "update users set "
            + "  username=?, password=?, email=?, phone=?, admin=?, disabled=?, homeUrl=?, receiveAlarmEmails=?, "
            + "  receiveOwnAuditEvents=? " + "where id=?";

    // Change Request #3 Implemented
    void updateUser(User user) {
        String username = (user.getUsername() != null) ? user.getUsername() : "";
        String password = (user.getPassword() != null) ? user.getPassword() : "";
        String email = (user.getEmail() != null) ? user.getEmail() : "";
        String phone = (user.getPhone() != null) ? user.getPhone() : "";
        String homeUrl = (user.getHomeUrl() != null) ? user.getHomeUrl() : "";

        ejt.update(
                USER_UPDATE,
                new Object[]{
                    username,
                    password,
                    email,
                    phone,
                    boolToChar(user.isAdmin()),
                    boolToChar(user.isDisabled()),
                    homeUrl,
                    user.getReceiveAlarmEmails(),
                    boolToChar(user.isReceiveOwnAuditEvents()),
                    user.getId()
                }
        );
        saveRelationalData(user);
    }

    private void saveRelationalData(final User user) {
        // Delete existing permissions.
        ejt.update("delete from dataSourceUsers where userId=?", new Object[] { user.getId() });
        ejt.update("delete from dataPointUsers where userId=?", new Object[] { user.getId() });

        // Save the new ones.
        ejt.batchUpdate("insert into dataSourceUsers (dataSourceId, userId) values (?,?)",
                new BatchPreparedStatementSetter() {
                    public int getBatchSize() {
                        return user.getDataSourcePermissions().size();
                    }

                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setInt(1, user.getDataSourcePermissions().get(i));
                        ps.setInt(2, user.getId());
                    }
                });
        ejt.batchUpdate("insert into dataPointUsers (dataPointId, userId, permission) values (?,?,?)",
                new BatchPreparedStatementSetter() {
                    public int getBatchSize() {
                        return user.getDataPointPermissions().size();
                    }

                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setInt(1, user.getDataPointPermissions().get(i).getDataPointId());
                        ps.setInt(2, user.getId());
                        ps.setInt(3, user.getDataPointPermissions().get(i).getPermission());
                    }
                });
    }

    public void deleteUser(final int userId) {
        getTransactionTemplate().execute(new TransactionCallbackWithoutResult() {
            @SuppressWarnings("synthetic-access")
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                Object[] args = new Object[] { userId };
                ejt.update("update userComments set userId=null where userId=?", args);
                ejt.update("delete from mailingListMembers where userId=?", args);
                ejt.update("update pointValueAnnotations set sourceId=null where sourceId=? and sourceType="
                        + SetPointSource.Type.USER, args);
                ejt.update("delete from userEvents where userId=?", args);
                ejt.update("update events set ackUserId=null, alternateAckSource="
                        + EventInstance.AlternateAcknowledgementSources.DELETED_USER + " where ackUserId=?", args);
                ejt.update("delete from users where id=?", args);
            }
        });
    }

    public void recordLogin(int userId) {
        ejt.update("update users set lastLogin=? where id=?", new Object[] { System.currentTimeMillis(), userId });
    }

    public void saveHomeUrl(int userId, String homeUrl) {
        ejt.update("update users set homeUrl=? where id=?", new Object[] { homeUrl, userId });
    }

    //
    //
    // User comments
    //
    private static final String USER_COMMENT_INSERT = "insert into userComments (userId, commentType, typeKey, ts, commentText) "
            + "values (?,?,?,?,?)";

    public void insertUserComment(int typeId, int referenceId, UserComment comment) {
        comment.setComment(Functions.truncate(comment.getComment(), 1024));
        ejt.update(USER_COMMENT_INSERT, new Object[] { comment.getUserId(), typeId, referenceId, comment.getTs(),
                comment.getComment() });
    }
}