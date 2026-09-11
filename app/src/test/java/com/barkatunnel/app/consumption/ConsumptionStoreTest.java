package com.barkatunnel.app.consumption;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteOpenHelper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.json.*;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE, application=Application.class)
public class ConsumptionStoreTest {
    private Context context;
    private final ConsumptionStore store=ConsumptionStore.INSTANCE;

    @Before public void before() throws Exception {
        context=RuntimeEnvironment.getApplication();
        clear();
    }
    @After public void after() throws Exception { clear(); }
    private void close() throws Exception {
        Field f=ConsumptionStore.class.getDeclaredField("helper");
        f.setAccessible(true);
        SQLiteOpenHelper h=(SQLiteOpenHelper)f.get(null);
        if(h!=null) h.close();
        f.set(null,null);
    }
    private void clear() throws Exception {
        close();
        context.deleteDatabase("barka_consumption.db");
    }
    private JSONObject row(String origin,String day,long up,long down) throws Exception {
        return new JSONObject().put("origin",origin).put("day",day).put("up",up).put("down",down);
    }
    private String file(JSONObject... rows) throws Exception {
        JSONArray a=new JSONArray();
        for(JSONObject r:rows) a.put(r);
        return new JSONObject().put("format","barka-consumption-1").put("days",a).toString();
    }

    @Test public void countsDeltasWithoutDuplicatesAndSurvivesRestart() throws Exception {
        store.record(context,"session1",100,200);
        store.record(context,"session1",100,200);
        store.record(context,"session1",90,190);
        assertEquals(300,store.totals(context).getTotal());
        store.record(context,"session1",140,280);
        assertEquals(420,store.totals(context).getTotal());
        close();
        store.record(context,"session1",140,280);
        assertEquals(420,store.totals(context).getTotal());
        store.record(context,"session2",10,20);
        assertEquals(450,store.totals(context).getToday());
    }
    @Test public void restoreAfterReinstallPreservesNewTrafficAndIsIdempotent() throws Exception {
        store.record(context,"old-session",100,200);
        String saved=store.export(context);
        clear();
        store.record(context,"new-session",40,60);
        store.restore(context,saved);
        assertEquals(400,store.totals(context).getTotal());
        store.restore(context,saved);
        assertEquals(400,store.totals(context).getTotal());
        store.record(context,"new-session",50,90);
        assertEquals(440,store.totals(context).getTotal());
        String merged=store.export(context);
        clear();
        store.restore(context,merged);
        assertEquals(440,store.totals(context).getTotal());
    }
    @Test public void yesterdayTodayAndTotalUseBurkinaCalendar() throws Exception {
        String origin=UUID.randomUUID().toString();
        LocalDate d=LocalDate.now(ZoneOffset.UTC);
        store.restore(context,file(row(origin,d.toString(),10,20),row(origin,d.minusDays(1).toString(),100,200),row(origin,d.minusDays(4).toString(),1000,2000)));
        ConsumptionStore.Totals t=store.totals(context);
        assertEquals(30,t.getToday());
        assertEquals(300,t.getYesterday());
        assertEquals(3330,t.getTotal());
        assertEquals(1110,t.getUp());
        assertEquals(2220,t.getDown());
    }
    @Test public void malformedRestoreRollsBackAndOlderBackupDoesNotSubtract() throws Exception {
        String origin=UUID.randomUUID().toString();
        String day=LocalDate.now(ZoneOffset.UTC).toString();
        store.restore(context,file(row(origin,day,100,200)));
        store.restore(context,file(row(origin,day,50,150)));
        assertEquals(300,store.totals(context).getTotal());
        try {
            store.restore(context,file(row(origin,day,1000,2000),row("invalid",day,1,1)));
            fail("Invalid origin must be rejected");
        } catch(IllegalArgumentException expected) { }
        assertEquals(300,store.totals(context).getTotal());
        try {
            store.restore(context,file(row(origin,day,-1,200)));
            fail("Negative counters must be rejected");
        } catch(IllegalArgumentException expected) { }
        assertEquals(300,store.totals(context).getTotal());
    }
}
