# **Mango M2M - Change Requests Implementation Guide**

## **Team Information**
- **Team Number:** 08
- **Team Members:**
  - Raj Desai
  - Nahush Patil
  - Mrunmayi Parker

## **Assignment - 03**
### **The Code changes can be located by**
All **refactored sections** in the code have been **commented** with the following tag:
```java
// Code Smell Refactoring
```
This comment is placed **above the modified code block** where a code smell was identified and fixed.

---

## **Files That Were Modified**
The following files contain refactored code:

1. **Dnp3DataSource.java**
   - **Issue:** Long Method (Bloater) in `doPoll(long time)`
   - **How to Locate:** Search for the comment:
     ```java
     // Code Smell #1
     ```

2. **SetPointSource.java**
   - **Issue:** Speculative Generality (Dispensable Code Smell)
   - **How to Locate:** Search for the comment:
     ```java
     // Code Smell #2
     ```

3. **EventDao.java**
   - **Issue:** Shotgun Surgery (Change Preventer)
   - **How to Locate:** Search for the comment:
     ```java
     // Code Smell #3
     ```


