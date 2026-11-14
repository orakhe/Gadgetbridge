/*  Rotador de caratulas: installs a random watch face from a folder on the phone every N seconds,
    alternating between two "slots" on the watch so the face in use is never touched.

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.XiaomiFWHelper;

public class XiaomiWatchfaceRotator {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiWatchfaceRotator.class);

    public static final String ACTION_CHANGED = "nodomain.freeyourgadget.gadgetbridge.rotator.CHANGED";
    public static final String ACTION_ROTATE_NOW = "nodomain.freeyourgadget.gadgetbridge.rotator.ROTATE_NOW";

    public static final String PREF_ENABLED = "rotator_enabled";
    public static final String PREF_FOLDER = "rotator_folder";
    public static final String PREF_INTERVAL = "rotator_interval_seconds";
    public static final String PREF_STATUS = "rotator_status";
    public static final String PREF_SLOT_FILE = "rotator_slot_file_";   // + slot number
    public static final String PREF_LAST_SLOT = "rotator_last_slot";

    public static final int MIN_INTERVAL = 5;
    private static final long WATCHDOG_MS = 4 * 60 * 1000;

    private enum State { IDLE, LISTING, DELETING, INSTALLING }

    private final XiaomiWatchfaceService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private State state = State.IDLE;
    private boolean started = false;
    private byte[] pendingBytes;
    private String pendingFile;
    private int targetSlot;
    private String targetId;

    private final Runnable tick = this::rotate;
    private final Runnable watchdog = () -> fail("tiempo agotado, se intenta en el siguiente turno");

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (ACTION_ROTATE_NOW.equals(intent.getAction())) {
                handler.removeCallbacks(tick);
                handler.post(tick);
            } else {
                schedule();
            }
        }
    };

    public XiaomiWatchfaceRotator(final XiaomiWatchfaceService service) {
        this.service = service;
    }

    private static SharedPreferences prefs() {
        return GBApplication.getPrefs().getPreferences();
    }

    private Context context() {
        return service.getSupport().getContext();
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(PREF_ENABLED, false);
    }

    public static long intervalSeconds() {
        long s;
        try {
            s = Long.parseLong(prefs().getString(PREF_INTERVAL, "3600"));
        } catch (final Exception e) {
            s = 3600;
        }
        return Math.max(MIN_INTERVAL, s);
    }

    public static void setStatus(final String text) {
        final String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        prefs().edit().putString(PREF_STATUS, time + "  " + text).apply();
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() {
        handler.post(() -> {
            if (!started) {
                final IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_CHANGED);
                filter.addAction(ACTION_ROTATE_NOW);
                LocalBroadcastManager.getInstance(context()).registerReceiver(receiver, filter);
                started = true;
            }
            if (isEnabled()) {
                setStatus("Reloj conectado. Próximo cambio en " + intervalSeconds() + " s");
            }
            schedule();
        });
    }

    public void stop() {
        handler.post(() -> {
            if (started) {
                try {
                    LocalBroadcastManager.getInstance(context()).unregisterReceiver(receiver);
                } catch (final Exception ignored) {
                }
                started = false;
            }
            handler.removeCallbacks(tick);
            handler.removeCallbacks(watchdog);
            if (state != State.IDLE && isEnabled()) {
                setStatus("Se desconectó el reloj a media rotación");
            } else if (isEnabled()) {
                setStatus("Reloj desconectado, rotación en pausa");
            }
            state = State.IDLE;
            pendingBytes = null;
        });
    }

    private void schedule() {
        handler.removeCallbacks(tick);
        if (started && isEnabled() && state == State.IDLE) {
            handler.postDelayed(tick, intervalSeconds() * 1000L);
        }
    }

    // ---------------------------------------------------------------- rotation steps

    private void rotate() {
        if (!started || state != State.IDLE) {
            return;
        }
        if (!service.isConnected()) {
            setStatus("Reloj desconectado, rotación en pausa");
            return;
        }
        if (service.isInstallInProgress()) {
            setStatus("Hay otra instalación en curso, se reintenta después");
            schedule();
            return;
        }
        final String folder = prefs().getString(PREF_FOLDER, null);
        if (folder == null) {
            setStatus("Falta elegir la carpeta de carátulas");
            return;
        }

        final List<String[]> files;
        try {
            files = listBinFiles(Uri.parse(folder));
        } catch (final Exception e) {
            LOG.error("Rotator: cannot list folder", e);
            fail("no pude leer la carpeta (¿se movió o se borró?)");
            return;
        }
        if (files.isEmpty()) {
            fail("la carpeta no tiene archivos .bin");
            return;
        }

        // avoid the faces that are already sitting in either slot, when possible
        final String inA = prefs().getString(PREF_SLOT_FILE + 1, "");
        final String inB = prefs().getString(PREF_SLOT_FILE + 2, "");
        final List<String[]> candidates = new ArrayList<>();
        for (final String[] f : files) {
            if (!f[1].equals(inA) && !f[1].equals(inB)) candidates.add(f);
        }
        if (candidates.isEmpty()) {
            for (final String[] f : files) {
                if (!f[1].equals(inA) || !f[1].equals(inB)) candidates.add(f);
            }
        }
        if (candidates.isEmpty()) candidates.addAll(files);
        final String[] pick = candidates.get(random.nextInt(candidates.size()));

        try {
            pendingBytes = readAll(context().getContentResolver(), Uri.parse(pick[0]));
        } catch (final Exception e) {
            LOG.error("Rotator: cannot read {}", pick[1], e);
            fail("no pude leer " + pick[1]);
            return;
        }
        if (pendingBytes.length < 0x80 || pendingBytes[0] != (byte) 0x5A || pendingBytes[1] != (byte) 0xA5) {
            fail(pick[1] + " no es una carátula válida para este reloj");
            return;
        }
        pendingFile = pick[1];

        state = State.LISTING;
        handler.postDelayed(watchdog, WATCHDOG_MS);
        setStatus("Elegida: " + pendingFile + ". Consultando el reloj…");
        service.requestWatchfaceList();
    }

    /** Called by the service every time a fresh watch-face list arrives. */
    void onWatchfaceList(final Set<UUID> all, final Set<UUID> user, final UUID active) {
        handler.post(() -> {
            if (state == State.LISTING) {
                final UUID slot1 = XiaomiWatchfaceService.toWatchfaceUUID(slotId(1));
                final UUID slot2 = XiaomiWatchfaceService.toWatchfaceUUID(slotId(2));
                if (slot1.equals(active)) {
                    targetSlot = 2;
                } else if (slot2.equals(active)) {
                    targetSlot = 1;
                } else {
                    // neither slot is on screen: use the one that was filled longest ago
                    targetSlot = prefs().getInt(PREF_LAST_SLOT, 2) == 1 ? 2 : 1;
                }
                targetId = slotId(targetSlot);
                final UUID target = XiaomiWatchfaceService.toWatchfaceUUID(targetId);
                if (all.contains(target)) {
                    if (!user.contains(target)) {
                        fail("el espacio " + targetSlot + " no se puede borrar");
                        return;
                    }
                    state = State.DELETING;
                    setStatus("Borrando el espacio " + targetSlot + "…");
                    service.deleteWatchface(target);
                } else {
                    install();
                }
            } else if (state == State.DELETING) {
                if (all.contains(XiaomiWatchfaceService.toWatchfaceUUID(targetId))) {
                    fail("el reloj no borró el espacio " + targetSlot);
                } else {
                    install();
                }
            }
        });
    }

    private void install() {
        final byte[] bytes = withId(pendingBytes, targetId);
        if (bytes == null) {
            fail(pendingFile + " tiene un ID que no sé reescribir");
            return;
        }
        final XiaomiFWHelper helper = new XiaomiFWHelper(bytes);
        if (!helper.isValid() || !helper.isWatchface()) {
            fail(pendingFile + " no pasó la validación de carátula");
            return;
        }
        state = State.INSTALLING;
        setStatus("Instalando " + pendingFile + " en el espacio " + targetSlot + "…");
        service.installWatchface(helper);
    }

    /** Called by the service when the watch rejects an install request. */
    void onInstallRejected(final int status) {
        handler.post(() -> {
            if (state == State.INSTALLING) {
                fail("el reloj rechazó la instalación (código " + status + ")");
            }
        });
    }

    /** Called by the service when an upload ends. */
    void onUploadFinished(final boolean success) {
        handler.post(() -> {
            if (state != State.INSTALLING) {
                return;
            }
            if (success) {
                prefs().edit()
                        .putString(PREF_SLOT_FILE + targetSlot, pendingFile)
                        .putInt(PREF_LAST_SLOT, targetSlot)
                        .apply();
                setStatus("Puesta: " + pendingFile + " (espacio " + targetSlot + "). Próximo cambio en " + intervalSeconds() + " s");
                done();
            } else {
                fail("falló el envío de " + pendingFile);
            }
        });
    }

    private void fail(final String why) {
        LOG.warn("Rotator: {}", why);
        setStatus("Error: " + why);
        done();
    }

    private void done() {
        handler.removeCallbacks(watchdog);
        state = State.IDLE;
        pendingBytes = null;
        schedule();
    }

    // ---------------------------------------------------------------- helpers

    /** Fixed ids for the two slots: 365299901 and 365299902. */
    static String slotId(final int slot) {
        return "36529990" + slot;
    }

    /** Returns a copy of the face with its id (zero-padded ASCII field 0x28..0x47) replaced. */
    static byte[] withId(final byte[] src, final String newId) {
        if (src.length < 0x48) return null;
        final byte[] out = src.clone();
        // the field must currently hold digits followed only by zeros
        int len = 0;
        while (len < 32 && out[0x28 + len] != 0) {
            if (out[0x28 + len] < '0' || out[0x28 + len] > '9') return null;
            len++;
        }
        for (int k = len; k < 32; k++) {
            if (out[0x28 + k] != 0) return null;
        }
        final byte[] id = newId.getBytes();
        for (int k = 0; k < 32; k++) {
            out[0x28 + k] = k < id.length ? id[k] : 0;
        }
        return out;
    }

    private static List<String[]> listBinFiles(final Uri tree) {
        final List<String[]> out = new ArrayList<>();
        final Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        final ContentResolver cr = GBApplication.getContext().getContentResolver();
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                final String name = c.getString(1);
                final String mime = c.getString(2);
                if (name == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                if (!name.toLowerCase().endsWith(".bin")) continue;
                out.add(new String[]{DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(), name});
            }
        }
        return out;
    }

    public static int countBinFiles(final Uri tree) {
        try {
            return listBinFiles(tree).size();
        } catch (final Exception e) {
            return -1;
        }
    }

    private static byte[] readAll(final ContentResolver cr, final Uri uri) throws Exception {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new Exception("null stream");
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 8 * 1024 * 1024) throw new Exception("file too big");
            }
            return bos.toByteArray();
        }
    }
}
