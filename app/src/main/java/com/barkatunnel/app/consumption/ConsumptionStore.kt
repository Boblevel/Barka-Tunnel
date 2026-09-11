package com.barkatunnel.app.consumption

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Local counters, never used as entitlement and never sent to the VPS. */
object ConsumptionStore {
    private class Database(context: Context) : SQLiteOpenHelper(context,"barka_consumption.db",null,1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE daily(origin TEXT NOT NULL,day TEXT NOT NULL,up INTEGER NOT NULL,down INTEGER NOT NULL,PRIMARY KEY(origin,day))")
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val recorder=java.util.concurrent.Executors.newSingleThreadExecutor()
    fun recordAsync(context: Context,session: String,up: Long,down: Long) {
        val app=context.applicationContext
        // Disk IO and a restore must never block tunnel health checks or stop.
        recorder.execute { runCatching { record(app,session,up,down) } }
    }
    private var helper: Database? = null
    private fun db(context: Context): SQLiteDatabase {
        if(helper==null)helper=Database(context.applicationContext)
        return helper!!.writableDatabase
    }
    private fun meta(db: SQLiteDatabase,key: String): String? = db.rawQuery("SELECT value FROM meta WHERE key=?",arrayOf(key)).use { if(it.moveToFirst())it.getString(0) else null }
    private fun put(db: SQLiteDatabase,key: String,value: String) { db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",arrayOf(key,value)) }
    fun today(): LocalDate=LocalDate.now(ZoneOffset.UTC)
    data class Totals(val yesterday: Long,val today: Long,val total: Long,val up: Long,val down: Long)
    @Synchronized fun record(context: Context,session: String,up: Long,down: Long) {
        if(up<0 || down<0)return
        val db=db(context);db.beginTransaction()
        try {
            val same=meta(db,"session")==session
            val lastUp=if(same)meta(db,"up")?.toLongOrNull() ?: 0L else 0L
            val lastDown=if(same)meta(db,"down")?.toLongOrNull() ?: 0L else 0L
            val deltaUp=(up-lastUp).coerceAtLeast(0);val deltaDown=(down-lastDown).coerceAtLeast(0)
            val origin=meta(db,"origin") ?: UUID.randomUUID().toString().also { put(db,"origin",it) }
            if(deltaUp>0 || deltaDown>0) {
                val day=today().toString()
                db.execSQL("INSERT OR IGNORE INTO daily(origin,day,up,down) VALUES(?,?,0,0)",arrayOf(origin,day))
                db.execSQL("UPDATE daily SET up=up+?,down=down+? WHERE origin=? AND day=?",arrayOf(deltaUp,deltaDown,origin,day))
            }
            put(db,"session",session);put(db,"up",maxOf(up,lastUp).toString());put(db,"down",maxOf(down,lastDown).toString())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun totals(context: Context): Totals {
        val day=today()
        return db(context).rawQuery("SELECT COALESCE(SUM(CASE WHEN day=? THEN up+down ELSE 0 END),0),COALESCE(SUM(CASE WHEN day=? THEN up+down ELSE 0 END),0),COALESCE(SUM(up),0),COALESCE(SUM(down),0) FROM daily",arrayOf(day.minusDays(1).toString(),day.toString())).use {
            it.moveToFirst();val up=it.getLong(2);val down=it.getLong(3);Totals(it.getLong(0),it.getLong(1),up+down,up,down)
        }
    }
    @Synchronized fun export(context: Context): String {
        val rows=JSONArray()
        db(context).rawQuery("SELECT origin,day,up,down FROM daily ORDER BY origin,day",null).use {
            while(it.moveToNext())rows.put(JSONObject().put("origin",it.getString(0)).put("day",it.getString(1)).put("up",it.getLong(2)).put("down",it.getLong(3)))
        }
        return JSONObject().put("format","barka-consumption-1").put("days",rows).toString()
    }
    @Synchronized fun restore(context: Context,text: String) {
        require(text.length<=4_000_000)
        val root=JSONObject(text);require(root.getString("format")=="barka-consumption-1")
        val rows=root.getJSONArray("days");require(rows.length()<=20000)
        val db=db(context);db.beginTransaction()
        try {
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i);val origin=UUID.fromString(row.getString("origin")).toString()
                val day=LocalDate.parse(row.getString("day"));require(day.year in 2026..2200)
                val up=row.getLong("up");val down=row.getLong("down")
                require(up in 0..100_000_000_000_000L && down in 0..100_000_000_000_000L)
                db.execSQL("INSERT OR IGNORE INTO daily(origin,day,up,down) VALUES(?,?,0,0)",arrayOf(origin,day.toString()))
                db.execSQL("UPDATE daily SET up=MAX(up,?),down=MAX(down,?) WHERE origin=? AND day=?",arrayOf(up,down,origin,day.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
