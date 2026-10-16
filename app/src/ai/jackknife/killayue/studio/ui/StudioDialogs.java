package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.*;
import android.content.*;
import android.text.InputType;
import android.widget.*;
final class StudioDialogs {
    interface Number {
        void value(double v) throws Exception;
    }
    static void number(final Context c, String title, double initial, final Number apply) {
        final EditText input = new EditText(c);
        input.setText(String.valueOf(initial));
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        new Lux.Builder(c)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Apply",
                               new DialogInterface.OnClickListener() {
                                   public void onClick(DialogInterface d, int w) {
                                       try {
                                           double v = Double.parseDouble(input.getText().toString());
                                           if (!Double.isFinite(v))
                                               throw new IllegalArgumentException("Enter a finite number.");
                                           apply.value(v);
                                       } catch (Exception e) {
                                           Ui.toast(c, e.getMessage());
                                       }
                                   }
                               })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
