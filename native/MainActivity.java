package top.izuna.foliamajor;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 連結模式的原生層：讀取權限、檔案列舉、標籤探測與本機 Range 串流服務。
        // 一定要在 super.onCreate 之後，WebView 才由 Bridge 建好。
        FoliaNativeStorage.attach(this);
    }
}
