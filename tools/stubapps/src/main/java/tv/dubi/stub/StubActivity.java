package tv.dubi.stub;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** A full-screen card naming the stand-in app; Back returns to the launcher. */
public class StubActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView t = new TextView(this);
        t.setText(getString(R.string.app_name) + "\n(stub app)");
        t.setTextSize(48);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xffffffff);
        t.setBackgroundColor(0xff202020);
        setContentView(t);
    }
}
