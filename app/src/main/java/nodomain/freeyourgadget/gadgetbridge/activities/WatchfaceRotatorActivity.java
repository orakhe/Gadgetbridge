/*  Rotador de caratulas: settings screen.

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiWatchfaceRotator;

public class WatchfaceRotatorActivity extends AbstractGBActivity {
    private static final String[] UNITS = {"segundos", "minutos", "horas"};
    private static final int[] FACTORS = {1, 60, 3600};

    private SharedPreferences prefs;
    private TextView folderText;
    private TextView statusText;
    private EditText intervalEdit;
    private Spinner unitSpinner;
    private Switch enabledSwitch;

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (XiaomiWatchfaceRotator.PREF_STATUS.equals(key)) {
            runOnUiThread(this::refreshStatus);
        }
    };

    private final ActivityResultLauncher<Uri> pickFolder = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (final Exception e) {
                    Toast.makeText(this, "No pude guardar el permiso de la carpeta", Toast.LENGTH_LONG).show();
                }
                prefs.edit().putString(XiaomiWatchfaceRotator.PREF_FOLDER, uri.toString()).apply();
                refreshFolder();
                notifyRotator(XiaomiWatchfaceRotator.ACTION_CHANGED);
            });

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Rotador de carátulas");
        prefs = GBApplication.getPrefs().getPreferences();

        final int pad = dp(16);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Rotación activa");
        enabledSwitch.setTextSize(18);
        enabledSwitch.setChecked(XiaomiWatchfaceRotator.isEnabled());
        enabledSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(XiaomiWatchfaceRotator.PREF_ENABLED, on).apply();
            XiaomiWatchfaceRotator.setStatus(on ? "Rotación activada" : "Rotación detenida");
            notifyRotator(XiaomiWatchfaceRotator.ACTION_CHANGED);
        });
        root.addView(enabledSwitch);

        root.addView(label("Carpeta con los archivos .bin"));
        folderText = new TextView(this);
        root.addView(folderText);
        final Button folderButton = new Button(this);
        folderButton.setText("Elegir carpeta");
        folderButton.setOnClickListener(v -> pickFolder.launch(null));
        root.addView(folderButton);

        root.addView(label("Cambiar cada"));
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        intervalEdit = new EditText(this);
        intervalEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalEdit.setMinEms(4);
        row.addView(intervalEdit);
        unitSpinner = new Spinner(this);
        unitSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, UNITS));
        row.addView(unitSpinner);
        final Button save = new Button(this);
        save.setText("Guardar");
        save.setOnClickListener(v -> saveInterval());
        row.addView(save);
        root.addView(row);
        showInterval();

        final Button now = new Button(this);
        now.setText("Rotar ahora");
        now.setOnClickListener(v -> {
            if (prefs.getString(XiaomiWatchfaceRotator.PREF_FOLDER, null) == null) {
                Toast.makeText(this, "Primero elige la carpeta", Toast.LENGTH_SHORT).show();
                return;
            }
            XiaomiWatchfaceRotator.setStatus("Rotación manual solicitada…");
            notifyRotator(XiaomiWatchfaceRotator.ACTION_ROTATE_NOW);
        });
        root.addView(now);

        root.addView(label("Estado"));
        statusText = new TextView(this);
        statusText.setTextSize(16);
        root.addView(statusText);

        final TextView help = new TextView(this);
        help.setPadding(0, dp(24), 0, 0);
        help.setText("Cómo funciona: el rotador usa dos espacios en el reloj (IDs 365299901 y 365299902). "
                + "En cada turno borra el espacio que no está en pantalla, instala ahí una carátula al azar de la carpeta y la pone. "
                + "Tus demás carátulas no se tocan.\n\n"
                + "El tiempo cuenta desde que termina una instalación. Solo rota mientras el reloj está conectado a esta app. "
                + "No uses al mismo tiempo el Gadgetbridge normal ni Mi Fitness.");
        root.addView(help);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(listener);
        refreshFolder();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
        super.onPause();
    }

    private void showInterval() {
        final long s = XiaomiWatchfaceRotator.intervalSeconds();
        int unit = 0;
        if (s % 3600 == 0) unit = 2;
        else if (s % 60 == 0) unit = 1;
        intervalEdit.setText(String.valueOf(s / FACTORS[unit]));
        unitSpinner.setSelection(unit);
    }

    private void saveInterval() {
        long v;
        try {
            v = Long.parseLong(intervalEdit.getText().toString().trim());
        } catch (final Exception e) {
            Toast.makeText(this, "Escribe un número", Toast.LENGTH_SHORT).show();
            return;
        }
        long seconds = v * FACTORS[unitSpinner.getSelectedItemPosition()];
        if (seconds < XiaomiWatchfaceRotator.MIN_INTERVAL) {
            seconds = XiaomiWatchfaceRotator.MIN_INTERVAL;
            Toast.makeText(this, "Mínimo " + seconds + " segundos", Toast.LENGTH_SHORT).show();
        }
        prefs.edit().putString(XiaomiWatchfaceRotator.PREF_INTERVAL, String.valueOf(seconds)).apply();
        showInterval();
        XiaomiWatchfaceRotator.setStatus("Intervalo: " + seconds + " s");
        notifyRotator(XiaomiWatchfaceRotator.ACTION_CHANGED);
    }

    private void refreshFolder() {
        final String folder = prefs.getString(XiaomiWatchfaceRotator.PREF_FOLDER, null);
        if (folder == null) {
            folderText.setText("(sin elegir)");
            return;
        }
        final Uri uri = Uri.parse(folder);
        final int n = XiaomiWatchfaceRotator.countBinFiles(uri);
        final String path = Uri.decode(uri.getLastPathSegment() != null ? uri.getLastPathSegment() : folder);
        folderText.setText(path + (n < 0 ? "\n(no se puede leer, elígela otra vez)" : "\n" + n + " carátulas .bin"));
    }

    private void refreshStatus() {
        statusText.setText(prefs.getString(XiaomiWatchfaceRotator.PREF_STATUS, "Sin actividad todavía"));
    }

    private void notifyRotator(final String action) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(action));
    }

    private TextView label(final String text) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    private int dp(final int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
