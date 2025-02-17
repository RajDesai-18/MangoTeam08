# **Mango M2M - Change Requests Implementation Guide**

## **Team Information**
- **Team Number:** 08
- **Team Members:**
  - Raj Desai
  - Nahush Patil
  - Mrunmayi Parker

## **Tracking Changes**
Each change request is clearly marked in the code using the following format:
```java
// Change Request #<*> Implemented
```
To find all changes, run:
```sh
grep -r "Change Request # " .
```

### **Change Request Breakdown**
#### **CR #2: Mute Notifications by Default**
- **Files Modified:**
  - `MiscDwr.java`
  - `User.java`
  - `soundmanager2.js`
- **Fix Applied:**
  - Ensured notifications are muted by default for new users.
  - Maintained user preference when toggling mute/unmute.
- **Verification:**
  - Log in as a new user and confirm that `mango.soundPlayer.isMute();` returns `true` in the browser console.
  - Toggle mute/unmute and verify persistence.

#### **CR #3: Fix Admin Password Change Bug**
- **Files Modified:**
  - `UserDao.java` or `UserService.java`
  - `MiscDwr.java`
- **Fix Applied:**
  - Prevented `NULL` values from causing SQL errors during password updates.
  - Ensured empty fields default to appropriate values.
- **Verification:**
  - Attempt to change the admin password.
  - Verify successful update without database errors.
  - Check database entry to confirm the password change.
