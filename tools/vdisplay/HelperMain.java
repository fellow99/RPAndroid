package helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.view.Surface;

import java.io.FileOutputStream;

/**
 * M1-1 shell helper v2 (app_process, shell uid 2000).
 * Uses a public DisplayManager.createVirtualDisplay built with a
 * shell (com.android.shell) context + full hidden flags + ImageReader capture.
 *
 * Usage: app_process /system/bin helper.HelperMain <w> <h> <dpi> <package> [component]
 */
public class HelperMain {

    private static final int FLAG_PUBLIC                    = 1 << 0;
    private static final int FLAG_OWN_CONTENT_ONLY          = 1 << 3;
    private static final int FLAG_SUPPORTS_TOUCH            = 1 << 6;
    private static final int FLAG_ROTATES_WITH_CONTENT      = 1 << 7;
    private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL= 1 << 8;
    private static final int FLAG_TRUSTED                   = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP         = 1 << 11;
    private static final int FLAG_ALWAYS_UNLOCKED           = 1 << 12;
    private static final int FLAG_TOUCH_FEEDBACK_DISABLED   = 1 << 13;
    private static final int FLAG_OWN_FOCUS                 = 1 << 14;
    private static final int FLAG_STEAL_TOP_FOCUS_DISABLED  = 1 << 16;

    public static void main(String[] args) throws Exception {
        int w = Integer.parseInt(args.length > 0 ? args[0] : "1080");
        int h = Integer.parseInt(args.length > 1 ? args[1] : "2340");
        int dpi = Integer.parseInt(args.length > 2 ? args[2] : "440");
        String pkg = args.length > 3 ? args[3] : "com.android.settings";
        String component = args.length > 4 ? args[4] : null;

        System.out.println("== HelperMain v2 uid=" + android.os.Process.myUid());
        Looper.prepareMainLooper();

        // shell context (com.android.shell, uid 2000)
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Object sysCtx = thread.getClass().getMethod("getSystemContext").invoke(thread);
        Context shellCtx = ((Context) sysCtx).createPackageContext("com.android.shell", 0);
        System.out.println("== shellCtx pkg=" + shellCtx.getPackageName());

        // reflectively construct DisplayManager bound to shell context
        DisplayManager dm = DisplayManager.class.getDeclaredConstructor(Context.class)
                .newInstance(shellCtx);

        // ImageReader capture surface
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        Surface surface = reader.getSurface();

        int sdk = android.os.Build.VERSION.SDK_INT;
        int flags = baseFlags() | FLAG_TOUCH_FEEDBACK_DISABLED;
        boolean trusted = true;
        VirtualDisplay vd;
        try {
            flags |= FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED;
            if (sdk >= 34) flags |= FLAG_OWN_FOCUS | FLAG_STEAL_TOP_FOCUS_DISABLED;
            vd = dm.createVirtualDisplay("mfa-agent", w, h, dpi, surface, flags);
        } catch (SecurityException e) {
            trusted = false;
            System.out.println("== trusted rejected, retrying untrusted: " + e);
            flags = baseFlags() | FLAG_TOUCH_FEEDBACK_DISABLED;
            vd = dm.createVirtualDisplay("mfa-agent", w, h, dpi, surface, flags);
        }
        int id = vd.getDisplay().getDisplayId();
        System.out.println("== CREATED_VIRTUAL_DISPLAY id=" + id + " trusted=" + trusted
                + " flags=0x" + Integer.toHexString(flags)
                + " valid=" + vd.getDisplay().isValid()
                + " flagsInfo=" + vd.getDisplay().getFlags());
        System.out.println("== MFA_VD_ID=" + id);
        System.out.flush();

        // launch target app onto the display
        String target = (component != null && component.startsWith(".")) ? pkg + "/" + component : (component != null ? component : pkg);
        String cmd = "am start --display " + id + " -n " + target;
        System.out.println("== exec: " + cmd);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            p.waitFor();
        } catch (Throwable t) {
            System.out.println("== am start err: " + t);
        }
        Thread.sleep(6000);

        // capture before-injection frame
        capture("/data/local/tmp/mfa_before.png", reader);
        Thread.sleep(14000); // window for the driver to inject input
        // capture after-injection frame
        capture("/data/local/tmp/mfa_after.png", reader);

        Thread.sleep(5000L); // keep display alive; outer test will kill
    }

    private static void capture(String path, ImageReader reader) {
        try {
            Image img = reader.acquireLatestImage();
            if (img != null) {
                int pw = img.getWidth(), ph = img.getHeight();
                android.media.Image.Plane plane = img.getPlanes()[0];
                Bitmap bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(plane.getBuffer());
                FileOutputStream fos = new FileOutputStream(path);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush(); fos.close();
                img.close();
                System.out.println("== FRAME_SAVED " + path + " " + pw + "x" + ph);
            } else {
                System.out.println("== FRAME_NULL (no frame acquired) @ " + path);
            }
        } catch (Throwable t) {
            System.out.println("== frame capture err @ " + path + ": " + t);
        }
    }

    private static int baseFlags() {
        return FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH
                | FLAG_ROTATES_WITH_CONTENT | FLAG_DESTROY_CONTENT_ON_REMOVAL;
    }
}
