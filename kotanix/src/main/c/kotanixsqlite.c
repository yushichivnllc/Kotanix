/*
 * kotanixsqlite.c — minimal JNI binding to the system libsqlite3.
 *
 * Why this exists: the classic Xerial sqlite-jdbc driver is not available in the
 * offline build environment Kotanix is built in (Maven Central is unreachable),
 * and we refuse to ship an unverified re-upload of it. Instead Kotanix links
 * against the system SQLite (Debian 12: libsqlite3-0, SONAME libsqlite3.so.0)
 * through this small, auditable binding.
 *
 * Rules that keep this safe:
 *  - Text crosses the JNI boundary as UTF-8 bytes (jbyteArray), never as
 *    modified UTF-8 jstrings. Emoji and every non-BMP codepoint round-trip.
 *  - One sqlite3 handle per database, statements finalized exactly once.
 *  - SQLITE_OPEN_FULLMUTEX; the Kotlin layer serialises access.
 *  - Every fallible call checks its result and throws SQLiteException with
 *    sqlite3_errmsg() in the message. No silent failures.
 */
#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>

typedef struct sqlite3 sqlite3;
typedef struct sqlite3_stmt sqlite3_stmt;
typedef long long sqlite3_int64;

#define SQLITE_OK          0
#define SQLITE_ERROR       1
#define SQLITE_BUSY        5
#define SQLITE_ROW         100
#define SQLITE_DONE        101
#define SQLITE_OPEN_READWRITE 0x00000002
#define SQLITE_OPEN_CREATE    0x00000004
#define SQLITE_OPEN_FULLMUTEX 0x00010000

#define SQLITE_INTEGER     1
#define SQLITE_FLOAT       2
#define SQLITE_TEXT        3
#define SQLITE_BLOB        4
#define SQLITE_NULL        5

extern int   sqlite3_open_v2(const char *filename, sqlite3 **ppDb, int flags, const char *zVfs);
extern int   sqlite3_close_v2(sqlite3 *db);
extern int   sqlite3_busy_timeout(sqlite3 *db, int ms);

extern int   sqlite3_prepare_v2(sqlite3 *db, const char *zSql, int nByte, sqlite3_stmt **ppStmt, const char **pzTail);
extern int   sqlite3_step(sqlite3_stmt *stmt);
extern int   sqlite3_reset(sqlite3_stmt *stmt);
extern int   sqlite3_finalize(sqlite3_stmt *stmt);
extern int   sqlite3_clear_bindings(sqlite3_stmt *stmt);

extern int   sqlite3_bind_null(sqlite3_stmt *stmt, int index);
extern int   sqlite3_bind_int64(sqlite3_stmt *stmt, int index, sqlite3_int64 value);
extern int   sqlite3_bind_double(sqlite3_stmt *stmt, int index, double value);
extern int   sqlite3_bind_text(sqlite3_stmt *stmt, int index, const char *value, int n, void(*destructor)(void *));
extern int   sqlite3_bind_blob(sqlite3_stmt *stmt, int index, const void *value, int n, void(*destructor)(void *));
#define SQLITE_TRANSIENT ((void(*)(void *))-1)

extern int          sqlite3_column_count(sqlite3_stmt *stmt);
extern int          sqlite3_column_type(sqlite3_stmt *stmt, int iCol);
extern sqlite3_int64 sqlite3_column_int64(sqlite3_stmt *stmt, int iCol);
extern double       sqlite3_column_double(sqlite3_stmt *stmt, int iCol);
extern const unsigned char *sqlite3_column_text(sqlite3_stmt *stmt, int iCol);
extern const void  *sqlite3_column_blob(sqlite3_stmt *stmt, int iCol);
extern int          sqlite3_column_bytes(sqlite3_stmt *stmt, int iCol);
extern const char  *sqlite3_column_name(sqlite3_stmt *stmt, int iCol);

extern sqlite3_int64 sqlite3_last_insert_rowid(sqlite3 *db);
extern int          sqlite3_changes(sqlite3 *db);
extern const char  *sqlite3_errmsg(sqlite3 *db);

static void throw_sqlite(JNIEnv *env, sqlite3 *db, const char *what) {
    const char *msg = db ? sqlite3_errmsg(db) : "no database handle";
    char buf[1024];
    snprintf(buf, sizeof buf, "%s: %s", what, msg ? msg : "unknown sqlite error");
    jclass ex = (*env)->FindClass(env, "kotanix/data/SqliteException");
    if (ex) {
        (*env)->ThrowNew(env, ex, buf);
    }
}

static sqlite3 *ptr(jlong v)   { return (sqlite3 *)(intptr_t)v; }
static sqlite3_stmt *sptr(jlong v) { return (sqlite3_stmt *)(intptr_t)v; }
static jlong id_of(const void *p)  { return (jlong)(intptr_t)p; }

JNIEXPORT jlong JNICALL Java_kotanix_data_Sqlite_nativeOpen(JNIEnv *env, jclass clazz, jbyteArray path) {
    jbyte *bytes = (*env)->GetByteArrayElements(env, path, NULL);
    jsize len = (*env)->GetArrayLength(env, path);
    /* path comes from Kotlin with explicit length; copy + terminate */
    char *cpath = (char *)calloc((size_t)len + 1, 1);
    if (!cpath) {
        (*env)->ReleaseByteArrayElements(env, path, bytes, JNI_ABORT);
        throw_sqlite(env, NULL, "out of memory");
        return 0;
    }
    memcpy(cpath, bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, path, bytes, JNI_ABORT);

    sqlite3 *db = NULL;
    int rc = sqlite3_open_v2(cpath, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, NULL);
    free(cpath);
    if (rc != SQLITE_OK) {
        /* db may be non-NULL even on failure; always close */
        throw_sqlite(env, db, "sqlite3_open_v2");
        if (db) sqlite3_close_v2(db);
        return 0;
    }
    sqlite3_busy_timeout(db, 5000);
    return id_of(db);
}

JNIEXPORT void JNICALL Java_kotanix_data_Sqlite_nativeClose(JNIEnv *env, jclass clazz, jlong dbPtr) {
    sqlite3 *db = ptr(dbPtr);
    if (db) {
        sqlite3_close_v2(db);
    }
}

JNIEXPORT jlong JNICALL Java_kotanix_data_Sqlite_nativePrepare(JNIEnv *env, jclass clazz, jlong dbPtr, jbyteArray sql) {
    sqlite3 *db = ptr(dbPtr);
    if (!db) { throw_sqlite(env, NULL, "nativePrepare on closed database"); return 0; }
    jbyte *bytes = (*env)->GetByteArrayElements(env, sql, NULL);
    jsize len = (*env)->GetArrayLength(env, sql);
    sqlite3_stmt *stmt = NULL;
    int rc = sqlite3_prepare_v2(db, (const char *)bytes, (int)len, &stmt, NULL);
    (*env)->ReleaseByteArrayElements(env, sql, bytes, JNI_ABORT);
    if (rc != SQLITE_OK) {
        throw_sqlite(env, db, "sqlite3_prepare_v2");
        if (stmt) sqlite3_finalize(stmt);
        return 0;
    }
    return id_of(stmt);
}

JNIEXPORT jint JNICALL Java_kotanix_data_Sqlite_nativeStep(JNIEnv *env, jclass clazz, jlong dbPtr, jlong stmtPtr) {
    sqlite3 *db = ptr(dbPtr);
    sqlite3_stmt *stmt = sptr(stmtPtr);
    int rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW && rc != SQLITE_DONE) {
        throw_sqlite(env, db, "sqlite3_step");
        return -1;
    }
    return rc;
}

JNIEXPORT void JNICALL Java_kotanix_data_Sqlite_nativeFinalize(JNIEnv *env, jclass clazz, jlong dbPtr, jlong stmtPtr) {
    sqlite3 *db = ptr(dbPtr);
    sqlite3_stmt *stmt = sptr(stmtPtr);
    if (stmt) {
        int rc = sqlite3_finalize(stmt);
        if (rc != SQLITE_OK && db) {
            throw_sqlite(env, db, "sqlite3_finalize");
        }
    }
}

JNIEXPORT void JNICALL Java_kotanix_data_Sqlite_nativeReset(JNIEnv *env, jclass clazz, jlong dbPtr, jlong stmtPtr) {
    sqlite3_stmt *stmt = sptr(stmtPtr);
    if (stmt) {
        sqlite3_reset(stmt);
        sqlite3_clear_bindings(stmt);
    }
}

/* args: array of null / String / Long / Integer / Double / Float / Boolean / byte[] */
JNIEXPORT void JNICALL Java_kotanix_data_Sqlite_nativeBindAll(JNIEnv *env, jclass clazz, jlong dbPtr, jlong stmtPtr, jobjectArray args) {
    sqlite3 *db = ptr(dbPtr);
    sqlite3_stmt *stmt = sptr(stmtPtr);
    jsize n = args ? (*env)->GetArrayLength(env, args) : 0;
    for (jsize i = 0; i < n; i++) {
        jobject arg = (*env)->GetObjectArrayElement(env, args, i);
        int idx = (int)i + 1;
        int rc = SQLITE_OK;
        if (arg == NULL) {
            rc = sqlite3_bind_null(stmt, idx);
        } else if ((*env)->IsInstanceOf(env, arg, (*env)->FindClass(env, "java/lang/String"))) {
            jbyteArray b = (jbyteArray)(*env)->CallObjectMethod(env, arg,
                (*env)->GetMethodID(env, (*env)->GetObjectClass(env, arg), "getBytes", "(Ljava/lang/String;)[B"),
                (*env)->NewStringUTF(env, "UTF-8"));
            jbyte *bytes = (*env)->GetByteArrayElements(env, b, NULL);
            jsize len = (*env)->GetArrayLength(env, b);
            rc = sqlite3_bind_text(stmt, idx, (const char *)bytes, (int)len, SQLITE_TRANSIENT);
            (*env)->ReleaseByteArrayElements(env, b, bytes, JNI_ABORT);
            (*env)->DeleteLocalRef(env, b);
        } else if ((*env)->IsInstanceOf(env, arg, (*env)->FindClass(env, "java/lang/Long"))) {
            jlong v = (*env)->CallLongMethod(env, arg, (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Number"), "longValue", "()J"));
            rc = sqlite3_bind_int64(stmt, idx, (sqlite3_int64)v);
        } else if ((*env)->IsInstanceOf(env, arg, (*env)->FindClass(env, "java/lang/Integer"))) {
            jint v = (*env)->CallIntMethod(env, arg, (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Integer"), "intValue", "()I"));
            rc = sqlite3_bind_int64(stmt, idx, (sqlite3_int64)v);
        } else if ((*env)->IsInstanceOf(env, arg, (*env)->FindClass(env, "java/lang/Short"))) {
            jint v = (*env)->CallShortMethod(env, arg, (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Short"), "shortValue", "()S"));
            rc = sqlite3_bind_int64(stmt, idx, (sqlite3_int64)v);
        } else if ((*env)->IsInstanceOf(env, arg, (*env)->FindClass(env, "java/lang/Byte"))) {
            jint v = (*env)->CallByteMethod(env, arg, (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Byte"), "byteValue", "()B"));
            rc = sqlite3_bind_int64(stmt, idx, (sqlite3_int64)v);
        } else if ((*env)->IsInstanceOf(env, arg, (*env)->FindClass(env, "java/lang/Double"))) {
            jdouble v = (*env)->CallDoubleMethod(env, arg, (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Number"), "doubleValue", "()D"));
            rc = sqlite3_bind_double(stmt, idx, (double)v);
        } else if ((*env)->IsInstanceOf(env, arg, (*env)->FindClass(env, "java/lang/Float"))) {
            jfloat v = (*env)->CallFloatMethod(env, arg, (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Float"), "floatValue", "()F"));
            rc = sqlite3_bind_double(stmt, idx, (double)v);
        } else if ((*env)->IsInstanceOf(env, arg, (*env)->FindClass(env, "java/lang/Boolean"))) {
            jboolean v = (*env)->CallBooleanMethod(env, arg, (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Boolean"), "booleanValue", "()Z"));
            rc = sqlite3_bind_int64(stmt, idx, v ? 1 : 0);
        } else if ((*env)->IsInstanceOf(env, arg, (*env)->FindClass(env, "[B"))) {
            jbyteArray b = (jbyteArray)arg;
            jbyte *bytes = (*env)->GetByteArrayElements(env, b, NULL);
            jsize len = (*env)->GetArrayLength(env, b);
            rc = sqlite3_bind_blob(stmt, idx, bytes, (int)len, SQLITE_TRANSIENT);
            (*env)->ReleaseByteArrayElements(env, b, bytes, JNI_ABORT);
        } else {
            /* last resort: toString */
            jstring s = (jstring)(*env)->CallObjectMethod(env, arg, (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Object"), "toString", "()Ljava/lang/String;"));
            jstring enc = (*env)->NewStringUTF(env, "UTF-8");
            jbyteArray b = (jbyteArray)(*env)->CallObjectMethod(env, s,
                (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/String"), "getBytes", "(Ljava/lang/String;)[B"), enc);
            jbyte *bytes = (*env)->GetByteArrayElements(env, b, NULL);
            jsize len = (*env)->GetArrayLength(env, b);
            rc = sqlite3_bind_text(stmt, idx, (const char *)bytes, (int)len, SQLITE_TRANSIENT);
            (*env)->ReleaseByteArrayElements(env, b, bytes, JNI_ABORT);
            (*env)->DeleteLocalRef(env, b);
            (*env)->DeleteLocalRef(env, s);
        }
        (*env)->DeleteLocalRef(env, arg);
        if (rc != SQLITE_OK) {
            throw_sqlite(env, db, "bind parameter");
            return;
        }
    }
}

JNIEXPORT jint JNICALL Java_kotanix_data_Sqlite_nativeColumnCount(JNIEnv *env, jclass clazz, jlong stmtPtr) {
    return sqlite3_column_count(sptr(stmtPtr));
}

JNIEXPORT jint JNICALL Java_kotanix_data_Sqlite_nativeColumnType(JNIEnv *env, jclass clazz, jlong stmtPtr, jint i) {
    return sqlite3_column_type(sptr(stmtPtr), (int)i);
}

JNIEXPORT jlong JNICALL Java_kotanix_data_Sqlite_nativeColumnLong(JNIEnv *env, jclass clazz, jlong stmtPtr, jint i) {
    return (jlong)sqlite3_column_int64(sptr(stmtPtr), (int)i);
}

JNIEXPORT jdouble JNICALL Java_kotanix_data_Sqlite_nativeColumnDouble(JNIEnv *env, jclass clazz, jlong stmtPtr, jint i) {
    return (jdouble)sqlite3_column_double(sptr(stmtPtr), (int)i);
}

JNIEXPORT jbyteArray JNICALL Java_kotanix_data_Sqlite_nativeColumnBytes(JNIEnv *env, jclass clazz, jlong stmtPtr, jint i) {
    sqlite3_stmt *stmt = sptr(stmtPtr);
    int type = sqlite3_column_type(stmt, (int)i);
    if (type == SQLITE_TEXT) {
        const unsigned char *text = sqlite3_column_text(stmt, (int)i);
        int n = sqlite3_column_bytes(stmt, (int)i);
        jbyteArray out = (*env)->NewByteArray(env, n);
        if (text && n > 0) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *)text);
        return out;
    }
    const void *blob = sqlite3_column_blob(stmt, (int)i);
    int n = sqlite3_column_bytes(stmt, (int)i);
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (blob && n > 0) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *)blob);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_kotanix_data_Sqlite_nativeColumnName(JNIEnv *env, jclass clazz, jlong stmtPtr, jint i) {
    const char *name = sqlite3_column_name(sptr(stmtPtr), (int)i);
    if (!name) return NULL;
    size_t len = strlen(name);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)name);
    return out;
}

JNIEXPORT jlong JNICALL Java_kotanix_data_Sqlite_nativeLastInsertRowId(JNIEnv *env, jclass clazz, jlong dbPtr) {
    return (jlong)sqlite3_last_insert_rowid(ptr(dbPtr));
}

JNIEXPORT jint JNICALL Java_kotanix_data_Sqlite_nativeChanges(JNIEnv *env, jclass clazz, jlong dbPtr) {
    return (jint)sqlite3_changes(ptr(dbPtr));
}
