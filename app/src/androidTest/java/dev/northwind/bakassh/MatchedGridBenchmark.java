package dev.northwind.bakassh;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import java.io.File;
import java.lang.reflect.Field;

/** Keeps the released SSH Activity at an exact visible grid; no renderer or transport changes. */
final class MatchedGridBenchmark {
    private final ZoomInstrumentation instrumentation;
    MatchedGridBenchmark(ZoomInstrumentation i) { instrumentation = i; }
    private static Object field(Object object, String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    void run(Bundle args) {
        Bundle result=new Bundle();
        Activity activity=null;
        try {
            int columns=Integer.parseInt(args.getString("columns","154"));
            int rows=Integer.parseInt(args.getString("rows","134"));
            Host host=HostStore.load(instrumentation.getTargetContext()).get(0);
            Intent intent=new Intent(instrumentation.getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("name",host.name).putExtra("host",host.host).putExtra("port",host.port)
                    .putExtra("user",host.user).putExtra("startup",host.startup);
            activity=instrumentation.startActivitySync(intent);
            Activity current=activity;
            TerminalView view=(TerminalView)field(activity,"view");
            instrumentation.runOnMainSync(() -> {
                // This benchmark sends no text. Keep IME changes out of the viewport measurement.
                current.getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                InputMethodManager imm=(InputMethodManager)current.getSystemService(Activity.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(current.getWindow().getDecorView().getWindowToken(),0);
                view.setTargetCols(columns);
            });
            SystemClock.sleep(1500);
            Throwable[] failure={null};
            instrumentation.runOnMainSync(() -> {
                try {
                    float cellHeight=(float)field(view,"cellH");
                    LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)view.getLayoutParams();
                    params.weight=0;
                    params.height=(int)Math.ceil(cellHeight*rows);
                    view.setLayoutParams(params);
                } catch(Throwable t) { failure[0]=t; }
            });
            if(failure[0]!=null)throw new AssertionError(failure[0]);
            SystemClock.sleep(1500);
            if(view.getCols()!=columns||view.getRows()!=rows)
                throw new AssertionError("grid mismatch: "+view.getGridInfo()+" view="+view.getWidth()+"x"+view.getHeight());
            String ready="GRID_READY view="+view.getWidth()+"x"+view.getHeight()+" "+view.getGridInfo();
            result.putString("stream",ready+"\n"); instrumentation.sendStatus(0,result);
            File stop=new File(activity.getExternalFilesDir(null),"matched-grid.stop");
            if(stop.exists())stop.delete();
            long until=SystemClock.uptimeMillis()+300_000;
            while(!stop.exists()&&SystemClock.uptimeMillis()<until)SystemClock.sleep(100);
            result.putString("stream","MATCHED_GRID_FINISHED\n");
            instrumentation.finish(Activity.RESULT_OK,result);
        } catch(Throwable t) {
            result.putString("stream",android.util.Log.getStackTraceString(t));
            instrumentation.finish(Activity.RESULT_CANCELED,result);
        } finally {
            if(activity!=null) {
                Activity current=activity;
                instrumentation.runOnMainSync(current::finish);
            }
        }
    }
}
