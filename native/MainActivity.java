package top.izuna.foliamajor;

import android.os.Bundle;
import android.view.KeyEvent;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 連結模式的原生層：讀取權限、檔案列舉、標籤探測與本機 Range 串流服務。
        // 一定要在 super.onCreate 之後，WebView 才由 Bridge 建好。
        FoliaNativeStorage.attach(this);
    }

    /**
     * 方控按键：不少车机直接把 KeyEvent 丢给前景 Activity，不经过 MediaSession。
     * 认得媒体键就交给原生层转给页面，其余照常往下走。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && FoliaNativeStorage.handleHardwareKey(event.getKeyCode())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
