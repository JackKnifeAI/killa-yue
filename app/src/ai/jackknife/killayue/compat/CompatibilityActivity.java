package ai.jackknife.killayue.compat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;

/** Shared by Settings and the separate, permission-free volunteer APK. */
public final class CompatibilityActivity extends Activity {
    private static final int OPEN_REQUEST = 101, SAVE_REPORT = 102;
    private static final String REPO = "https://github.com/JackKnifeAI/killa-yue";
    private volatile boolean cancelled, running;
    private TextView status, preview;
    private Spinner driver;
    private LinearLayout content;
    private JSONObject report;
    private String pendingExport;
    private boolean lab;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        lab = getPackageName().endsWith(".compatlab");
        setTitle(lab ? "Killa YuE Compatibility Lab" : "Driver and compatibility checks");
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad); scroll.addView(content); setContentView(scroll);
        text("Help make Killa YuE work on your phone", 22);
        text("1. Stop song generation and other heavy jobs.\n2. Check capabilities first, then optionally test calculations.\n3. Review the report and choose whether to share it on GitHub.", 16);
        text("No music models are needed. Tests use small synthetic matrices and do not change your music settings. Keep this screen open. Calculation tests stop after 90 seconds, below 20% battery, at 40°C battery temperature, or when Android reports severe heat. A GPU driver bug can still freeze or restart the device; a timeout cannot guarantee recovery from a kernel fault.", 14);
        text("Reports contain phone model, Android/firmware, GPU capabilities, build hashes and test results. They exclude serial numbers, accounts, installed apps, IP addresses, songs and engine logs. Nothing uploads automatically. GitHub submissions are public.", 14);
        driver = new Spinner(this);
        driver.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[] {"System GPU driver", "Bundled Turnip (Adreno)", "CPU only"}));
        content.addView(driver);
        button("Check capabilities", new Runnable() { public void run() { choose("capabilities"); } });
        button("Test calculations", new Runnable() { public void run() { choose("math"); } });
        button("Stop", new Runnable() { public void run() { cancelled = true; status.setText("Stopping the test…"); } });
        if (lab) button("Open a retest request", new Runnable() { public void run() {
            if (running) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, OPEN_REQUEST);
        } });
        status = text("Ready. A capability check does not prove GPU calculation correctness.", 16);
        preview = text("", 13); preview.setTextIsSelectable(true);
        button("Review and open GitHub", new Runnable() { public void run() { github(); } });
        button("Save report as JSON", new Runnable() { public void run() {
            if (report == null || running) { status.setText("Finish a check first."); return; }
            pendingExport = report.toString();
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "killa-compatibility.json");
            startActivityForResult(i, SAVE_REPORT);
        } });
        try {
            File saved = new File(getFilesDir(), "compatibility/latest.json");
            if (saved.isFile()) {
                try (InputStream in = new FileInputStream(saved)) { report = new JSONObject(read(in, 65536)); }
                display("Previous report — run a new check after changing the APK or firmware.");
            }
        } catch (Exception ignored) { report = null; }
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0, 8, 0, 12);
        content.addView(view); return view;
    }
    private void button(String title, final Runnable action) {
        Button b = new Button(this); b.setText(title); content.addView(b);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { action.run(); } });
    }
    private void choose(String suite) {
        if (running) return;
        String selected = new String[] {"system", "turnip", "cpu"}[driver.getSelectedItemPosition()];
        try { confirm(new CompatibilityPlan(suite, selected, "", 0, "", "")); }
        catch (Exception e) { message(e.getMessage()); }
    }
    private void confirm(final CompatibilityPlan plan) {
        if (running) return;
        String detail = "Run " + plan.suite + " with " + plan.driver + ".\n\n";
        if (plan.retest()) detail += "Retest " + plan.requestId + " for GitHub issue #" + plan.issue
                + ". The installed APK and phone configuration must match the request.\n\n";
        detail += "This runs only built-in checks. You can stop, or leave this screen to cancel. Review the report before sharing.";
        new AlertDialog.Builder(this).setTitle(plan.retest() ? "Review retest request" : "Run compatibility check")
            .setMessage(detail).setPositiveButton("Run", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { run(plan); }
            }).setNegativeButton("Cancel", null).show();
    }
    private void run(final CompatibilityPlan plan) {
        if (running) return;
        cancelled = false; running = true; report = null; preview.setText(""); status.setText("Preparing local checks…");
        new Thread(new Runnable() { public void run() {
            try {
                CompatibilityRunner runner = new CompatibilityRunner(CompatibilityActivity.this,
                    new DiagnosticProcess.Stop() { public boolean requested() { return cancelled; } },
                    new CompatibilityRunner.Progress() { public void update(final String value) {
                        runOnUiThread(new Runnable() { public void run() { if (!isFinishing()) status.setText(value); } });
                    } });
                final JSONObject result = runner.run(plan);
                File folder = new File(getFilesDir(), "compatibility");
                File temporary = new File(folder, "latest.tmp"), saved = new File(folder, "latest.json");
                try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(result.toString().getBytes("UTF-8")); }
                if (!temporary.renameTo(saved)) throw new Exception("Could not save the local report");
                runOnUiThread(new Runnable() { public void run() {
                    running = false; report = result;
                    if (!isFinishing()) display("Finished: " + result.optString("status") + ". Passing small calculations does not certify full song generation.");
                } });
            } catch (final Exception error) {
                runOnUiThread(new Runnable() { public void run() { running = false; if (!isFinishing()) message(error.getMessage()); } });
            }
        } }, "compatibility-check").start();
    }
    private void display(String value) {
        status.setText(value);
        try { preview.setText(report.toString(2)); } catch (Exception ignored) { preview.setText(report.toString()); }
    }
    private void message(String value) { status.setText(value == null ? "The check could not complete." : value); }
    private void github() {
        if (report == null || running) { message("Finish a check first."); return; }
        final String body = "Compatibility report generated locally; shared by the tester.\n\n```json\n" + report.toString() + "\n```\n\nWhat I want to do / what failed:\n";
        final String title = "[Compatibility] " + report.optString("manufacturer") + " " + report.optString("model")
            + " — " + report.optString("driver") + " — " + report.optString("status");
        final Uri url = Uri.parse(REPO + "/issues/new").buildUpon().appendQueryParameter("template", "compatibility-request.md")
            .appendQueryParameter("title", title).appendQueryParameter("body", body).build();
        final boolean fits = url.toString().length() <= 7500;
        new AlertDialog.Builder(this).setTitle("Share this report publicly?")
            .setMessage("GitHub will receive the report shown above, linked to your GitHub account when you submit. "
                + (fits ? "The form will be filled in automatically. Review it and press Submit on GitHub." : "This report is too long for a browser link. It will be copied so you can paste it into the form."))
            .setPositiveButton(fits ? "Open filled-in report" : "Copy and open GitHub", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    if (!fits) ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Compatibility report", body));
                    try { startActivity(new Intent(Intent.ACTION_VIEW, fits ? url : Uri.parse(REPO + "/issues/new?template=compatibility-request.md"))); }
                    catch (Exception e) { message("No browser is available. Save the JSON report and submit it from another device."); }
                }
            }).setNegativeButton("Keep private", null).show();
    }
    static String read(InputStream input, int limit) throws Exception {
        if (input == null) throw new Exception("Cannot open that document");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[1024]; int n;
        while ((n = input.read(bytes)) != -1) {
            if (out.size() + n > limit) throw new Exception("Document is too large");
            out.write(bytes, 0, n);
        }
        return out.toString("UTF-8");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (request == SAVE_REPORT) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (out == null || pendingExport == null) throw new Exception("Cannot save report");
                    out.write(pendingExport.getBytes("UTF-8")); pendingExport = null;
                }
                message("Report saved where you chose.");
            } else if (request == OPEN_REQUEST && lab) {
                JSONObject json;
                try (InputStream in = getContentResolver().openInputStream(data.getData())) { json = new JSONObject(read(in, CompatibilityPlan.MAX_REQUEST_BYTES)); }
                if (json.getInt("schema") != 1 || json.getInt("suite_version") != CompatibilityPlan.SUITE_VERSION)
                    throw new Exception("This app does not support that request version");
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) if (!keys.next().matches("schema|suite_version|suite|driver|request_id|issue|candidate_apk_sha256|device_key"))
                    throw new Exception("The request contains unsupported fields");
                CompatibilityPlan plan = new CompatibilityPlan(json.getString("suite"), json.getString("driver"), json.getString("request_id"),
                    json.getInt("issue"), json.getString("candidate_apk_sha256"), json.getString("device_key"));
                if (!plan.retest()) throw new Exception("A retest request needs a request ID");
                confirm(plan);
            }
        } catch (Exception e) { message(e.getMessage()); }
    }
    @Override protected void onStop() { if (running) cancelled = true; super.onStop(); }
}
