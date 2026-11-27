import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

public class Gesture {
    static Object manager;
    static Method inject;
    static long down;

    static void touch(int action, int count, float x, float y, float span) throws Exception {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i=0; i<count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id=i;
            props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();
            coords[i].x=count==1 ? x : x+(i==0?-span/2:span/2);
            coords[i].y=y;
            coords[i].pressure=1;
            coords[i].size=1;
        }
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try {
            if (!(Boolean)inject.invoke(manager,e,0)) throw new IllegalStateException("injection rejected");
        } finally {e.recycle();}
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> cls=Class.forName("android.hardware.input.InputManagerGlobal");
        manager=cls.getMethod("getInstance").invoke(null);
        inject=cls.getMethod("injectInputEvent",InputEvent.class,int.class);
        float x=Float.parseFloat(args[1]), y=Float.parseFloat(args[2]);
        down=SystemClock.uptimeMillis();
        if(args[0].equals("pinch")){
            float f=Float.parseFloat(args[3]), span=900;
            touch(0,1,x-span/2,y,0); touch(261,2,x,y,span);
            for(int i=1;i<=48;i++){span=(float)(900*Math.pow(f,i/48.0));touch(2,2,x,y,span);SystemClock.sleep(16);}
            touch(262,2,x,y,span);touch(1,1,x-span/2,y,0);
        } else {
            for(int r=0;r<3;r++){
                long start=SystemClock.elapsedRealtimeNanos();
                for(int k=0;k<3;k++){
                    float dir=k%2==0?-1:1;
                    down=SystemClock.uptimeMillis();
                    long a=SystemClock.elapsedRealtimeNanos();
                    touch(0,1,x,y-dir*400,0);
                    for(int i=1;i<=60;i++){touch(2,1,x,y-dir*400+dir*i*800/60f,0);SystemClock.sleep(8);}
                    touch(1,1,x,y+dir*400,0);
                    System.out.println("stroke "+r+" "+a+" "+SystemClock.elapsedRealtimeNanos());
                    SystemClock.sleep(150);
                }
                System.out.println("phase "+r+" "+start+" "+SystemClock.elapsedRealtimeNanos());
                SystemClock.sleep(350);
            }
        }
        System.exit(0);
    }
}
