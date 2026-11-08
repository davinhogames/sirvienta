package com.sirvienta.android;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.AlarmClock;
import android.util.Log;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.text.Normalizer;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DeviceActionHandler {
    private static final String TAG = "SirvientaDeviceAction";

    static final class Result {
        final boolean success;
        final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private DeviceActionHandler() { }

    static Result executeLocalCommand(Context context, String text) {
        String normalized = normalize(text);
        Result externalAction = handleExternalCommand(context, text);
        if (externalAction != null) {
            return externalAction;
        }
        Pattern openAndReadNotifications = Pattern.compile(
                "^(?:oye\\s+)?(?:abre|inicia|entra\\s+en|m[eé]tete\\s+en)\\s+(.+?)\\s+y\\s+"
                        + "(?:mir[ae]|revis[ae]|lee|consulta|dime)\\s+(?:las?\\s+)?notificaciones$",
                Pattern.CASE_INSENSITIVE);
        Matcher openAndReadMatch = openAndReadNotifications.matcher(text.trim());
        if (openAndReadMatch.matches()) {
            String appName = openAndReadMatch.group(1).trim();
            Result opened = openApp(context, appName);
            if (!opened.success) {
                return opened;
            }
            Result notifications = NotificationAccessService.readActiveNotifications(appName);
            return new Result(
                    notifications.success,
                    opened.message + "\n" + notifications.message);
        }
        Pattern readNotifications = Pattern.compile(
                "^(?:oye\\s+)?(?:mir[ae]|revis[ae]|lee|consulta|dime)\\s+(?:las?\\s+)?"
                        + "notificaciones(?:\\s+de\\s+(.+))?$",
                Pattern.CASE_INSENSITIVE);
        Matcher readNotificationsMatch = readNotifications.matcher(text.trim());
        if (readNotificationsMatch.matches()) {
            return NotificationAccessService.readActiveNotifications(readNotificationsMatch.group(1));
        }
        Pattern call = Pattern.compile(
                "^(?:oye\\s+)?(?:llama|llamar|marca)\\s+(?:(?:a|al)\\s+)?(.+)$",
                Pattern.CASE_INSENSITIVE);
        Matcher callMatch = call.matcher(text.trim());
        if (callMatch.matches()) {
            return callContact(context, callMatch.group(1).trim());
        }
        if (normalized.matches(".*\\b(que hora es|dime la hora|hora actual)\\b.*")) {
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            return new Result(true, "Son las " + time + ".");
        }

        Pattern reminder = Pattern.compile(
                "^recu[eé]rdame\\s+(?:que\\s+)?(.+?)\\s+en\\s+(\\d+)\\s*"
                        + "(segundos?|minutos?|horas?)$",
                Pattern.CASE_INSENSITIVE);
        Matcher reminderMatch = reminder.matcher(text.trim());
        if (reminderMatch.matches()) {
            try {
                long amount = Long.parseLong(reminderMatch.group(2));
                String unit = normalize(reminderMatch.group(3));
                long multiplier = unit.startsWith("hora") ? 3_600_000L
                        : unit.startsWith("minuto") ? 60_000L : 1_000L;
                long delayMillis = Math.multiplyExact(amount, multiplier);
                String reminderText = reminderMatch.group(1).trim();
                LocalReminderScheduler.schedule(context, reminderText, delayMillis);
                return new Result(true, "Te recordaré “" + reminderText + "” en " + amount
                        + " " + unit + ". Android puede retrasar un poco el aviso para ahorrar batería.");
            } catch (ArithmeticException | IllegalArgumentException error) {
                Log.e(TAG, "No se pudo programar el recordatorio local.", error);
                return new Result(false, error.getMessage() == null
                        ? "No he podido programar ese recordatorio."
                        : error.getMessage());
            }
        }
        if (normalized.startsWith("recuerdame ")) {
            return new Result(
                    false,
                    "¿Cuándo quieres que te lo recuerde? Di, por ejemplo: “Recuérdame comprar leche en 20 minutos”.");
        }

        if (normalized.matches(".*\\b(lee mis notas|dime mis notas|que tengo apuntado|"
                + "que he apuntado|que recuerdas de mi)\\b.*")) {
            try {
                return new Result(true, LocalAssistantData.listNotes(context));
            } catch (org.json.JSONException error) {
                Log.e(TAG, "No se pudieron leer las notas locales.", error);
                return new Result(false, "No he podido leer las notas guardadas en este móvil.");
            }
        }
        Pattern note = Pattern.compile(
                "^(?:por favor\\s*)?(?:ap[uú]nta(?:me)?|anota(?:me)?|escribe(?:me)?|guarda(?:me)?)"
                        + "(?:\\s+una\\s+nota)?\\s+(?:que\\s+)?(.+)$",
                Pattern.CASE_INSENSITIVE);
        Matcher noteMatch = note.matcher(text.trim());
        if (!noteMatch.matches() && normalized.startsWith("recuerda que ")) {
            noteMatch = Pattern.compile("^recuerda que\\s+(.+)$", Pattern.CASE_INSENSITIVE)
                    .matcher(text.trim());
        }
        if (noteMatch.matches()) {
            String content = noteMatch.group(1).trim();
            if (content.isEmpty()) {
                return new Result(false, "Dime qué quieres que apunte.");
            }
            try {
                LocalAssistantData.addNote(context, content);
                return new Result(true, "He apuntado: " + content);
            } catch (org.json.JSONException error) {
                Log.e(TAG, "No se pudo guardar la nota local.", error);
                return new Result(false, "No he podido guardar la nota en este móvil.");
            }
        }

        Pattern timer = Pattern.compile(
                ".*\\btemporizador\\b.*?\\b(\\d+)\\s*(segundos?|minutos?|horas?)\\b.*");
        Matcher timerMatch = timer.matcher(normalized);
        if (timerMatch.matches()) {
            try {
                long amount = Long.parseLong(timerMatch.group(1));
                String unit = timerMatch.group(2);
                long multiplier = unit.startsWith("hora") ? 3_600L
                        : unit.startsWith("minuto") ? 60L : 1L;
                long seconds = Math.multiplyExact(amount, multiplier);
                if (seconds > Integer.MAX_VALUE) {
                    return new Result(false, "El temporizador es demasiado largo.");
                }
                JSONObject action = new JSONObject()
                        .put("seconds", (int) seconds)
                        .put("label", "Sirvienta");
                return setTimer(context, action);
            } catch (ArithmeticException | org.json.JSONException error) {
                Log.e(TAG, "La duración del temporizador no es válida.", error);
                return new Result(false, "No he podido interpretar la duración del temporizador.");
            }
        }
        if (normalized.contains("temporizador")) {
            return new Result(false, "Dime cuánto debe durar el temporizador, por ejemplo: 5 minutos.");
        }

        Pattern alarm = Pattern.compile(
                ".*\\balarma\\b.*?\\ba las\\s+(\\d{1,2})(?:[:.]([0-5]?\\d))?"
                        + "(?:\\s*(de la manana|de la tarde|de la noche|am|pm))?.*");
        Matcher alarmMatch = alarm.matcher(normalized);
        if (alarmMatch.matches()) {
            try {
                int hour = Integer.parseInt(alarmMatch.group(1));
                int minute = alarmMatch.group(2) == null ? 0 : Integer.parseInt(alarmMatch.group(2));
                String period = alarmMatch.group(3);
                if (period != null) {
                    if (hour < 1 || hour > 12) {
                        return new Result(false, "La hora de la alarma no es válida.");
                    }
                    if ((period.contains("tarde") || period.equals("pm")) && hour < 12) {
                        hour += 12;
                    } else if ((period.contains("manana") || period.equals("am")) && hour == 12) {
                        hour = 0;
                    } else if (period.contains("noche") && hour < 12) {
                        hour += 12;
                    }
                }
                JSONObject action = new JSONObject()
                        .put("hour", hour)
                        .put("minute", minute)
                        .put("label", "Sirvienta");
                return setAlarm(context, action);
            } catch (NumberFormatException | org.json.JSONException error) {
                Log.e(TAG, "La hora de la alarma no es válida.", error);
                return new Result(false, "No he podido interpretar la hora de la alarma.");
            }
        }
        if (normalized.contains("alarma")) {
            return new Result(false, "Dime a qué hora quieres poner la alarma, por ejemplo: a las 7:30.");
        }

        Pattern launch = Pattern.compile(
                "^(?:oye\\s+)?(?:abre|abrir|inicia|iniciar|ejecuta|ejecutar|"
                        + "entra\\s+en|m[eé]tete\\s+en|ve\\s+a)\\s+"
                        + "(?:la\\s+)?(?:aplicacion\\s+de\\s+|app\\s+de\\s+)?(.+)$",
                Pattern.CASE_INSENSITIVE);
        Matcher launchMatch = launch.matcher(normalized);
        if (launchMatch.matches()) {
            String appName = launchMatch.group(1).replaceAll("\\s+por favor$", "").trim();
            return openApp(context, appName);
        }
        return null;
    }

    private static Result callContact(Context context, String recipient) {
        if (recipient.isEmpty()) {
            return new Result(false, "Dime a quién quieres llamar.");
        }
        String number = recipient.replaceAll("[\\s().-]", "");
        if (number.matches("\\+?\\d{3,}")) {
            Intent dial = new Intent(
                    Intent.ACTION_DIAL,
                    Uri.fromParts("tel", number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(dial);
                return new Result(
                        true,
                        "He abierto el marcador con el número. Revisa el número y confirma la llamada.");
            } catch (ActivityNotFoundException | SecurityException error) {
                Log.e(TAG, "No se pudo abrir el marcador para iniciar la llamada.", error);
                return new Result(false, "No he podido abrir el marcador de llamadas.");
            }
        }

        Uri contact = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_FILTER_URI,
                Uri.encode(recipient));
        Intent showContact = new Intent(Intent.ACTION_VIEW, contact)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(showContact);
            return new Result(
                    true,
                    "He abierto la búsqueda de contactos para " + recipient
                            + ". Selecciona el contacto y confirma la llamada.");
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e(TAG, "No se pudo abrir la búsqueda del contacto solicitado.", error);
            return new Result(false, "No he podido abrir el contacto " + recipient + ".");
        }
    }

    private static Result handleExternalCommand(Context context, String text) {
        Pattern spotify = Pattern.compile(
                "^(?:oye\\s+)?(?:m[eé]tete|entra|abre|ve)\\s+(?:en|a)\\s+spotify\\s+(?:y\\s+)?"
                        + "(?:reproduce|pon(?:me)?|escucha|busca)\\s+"
                        + "(?:(?:la\\s+)?canci[oó]n\\s+)?(.+)$",
                Pattern.CASE_INSENSITIVE);
        Matcher spotifyMatch = spotify.matcher(text.trim());
        if (!spotifyMatch.matches()) {
            spotify = Pattern.compile(
                    "^(?:oye\\s+)?(?:reproduce|pon(?:me)?|escucha|busca)\\s+"
                            + "(?:(?:la\\s+)?canci[oó]n\\s+)?(.+?)\\s+(?:en|por)\\s+spotify$",
                    Pattern.CASE_INSENSITIVE);
            spotifyMatch = spotify.matcher(text.trim());
        }
        if (spotifyMatch.matches()) {
            String query = spotifyMatch.group(1).trim();
            if (query.isEmpty()) {
                return new Result(false, "Dime qué canción quieres escuchar en Spotify.");
            }
            Intent search = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("spotify:search:" + Uri.encode(query)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(search);
                return new Result(
                        true,
                        "He abierto Spotify buscando “" + query
                                + "”. Toca la canción para empezar a reproducirla.");
            } catch (ActivityNotFoundException | SecurityException error) {
                Log.w(TAG, "No se pudo abrir la búsqueda de Spotify; se probará en la web.", error);
                Intent webSearch = new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://open.spotify.com/search/"
                                + Uri.encode(query)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(webSearch);
                    return new Result(
                            true,
                            "He abierto la búsqueda de “" + query
                                    + "” en Spotify Web. Selecciona la canción para reproducirla.");
                } catch (ActivityNotFoundException | SecurityException webError) {
                    Log.e(TAG, "No hay una aplicación disponible para abrir Spotify.", webError);
                    return new Result(false, "No he podido abrir Spotify en este móvil.");
                }
            }
        }

        Pattern whatsapp = Pattern.compile(
                "^(?:oye\\s+)?(?:escr[ií]bele|escribe(?:le)?|manda(?:le)?|env[ií]ale)\\s+"
                        + "(?:un\\s+)?whats?app\\s+(?:a|para)\\s+(.+?)\\s+"
                        + "(?:diciendo(?:le)?\\s+que|que\\s+diga|con\\s+el\\s+mensaje(?:\\s+de)?)\\s+(.+)$",
                Pattern.CASE_INSENSITIVE);
        Matcher whatsappMatch = whatsapp.matcher(text.trim());
        if (!whatsappMatch.matches()) {
            whatsapp = Pattern.compile(
                    "^(?:oye\\s+)?(?:escr[ií]bele|escribe(?:le)?|manda(?:le)?|env[ií]ale)\\s+"
                            + "(?:a|para)\\s+(.+?)\\s+(?:un\\s+)?whats?app\\s+"
                            + "(?:diciendo(?:le)?\\s+que|que\\s+diga|con\\s+el\\s+mensaje(?:\\s+de)?)\\s+(.+)$",
                    Pattern.CASE_INSENSITIVE);
            whatsappMatch = whatsapp.matcher(text.trim());
        }
        if (!whatsappMatch.matches()) {
            return null;
        }

        String recipient = whatsappMatch.group(1).trim();
        String message = whatsappMatch.group(2).trim();
        if (recipient.isEmpty() || message.isEmpty()) {
            return new Result(false, "Dime a quién quieres escribir y qué mensaje preparo.");
        }
        Intent shareToWhatsApp = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .setPackage("com.whatsapp")
                .putExtra(Intent.EXTRA_TEXT, message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(shareToWhatsApp);
            return new Result(
                    true,
                    "He preparado para WhatsApp el mensaje para " + recipient
                            + ". Elige ese contacto y pulsa Enviar para confirmarlo.");
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w(TAG, "WhatsApp no está disponible; se probará un enlace web prellenado.", error);
            String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
            Intent webWhatsApp = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/?text=" + encodedMessage))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(webWhatsApp);
                return new Result(
                        true,
                        "He preparado el mensaje para " + recipient
                                + " en WhatsApp Web. Selecciona el contacto y confirma el envío.");
            } catch (ActivityNotFoundException | SecurityException webError) {
                Log.e(TAG, "No se pudo abrir WhatsApp.", webError);
                return new Result(false, "No he podido abrir WhatsApp en este móvil.");
            }
        }
    }

    static Result execute(Context context, JSONObject action) {
        if (action == null) {
            return null;
        }
        String type = action.optString("type");
        try {
            if ("open_app".equals(type)) {
                return openApp(context, action.optString("name"));
            }
            if ("set_alarm".equals(type)) {
                return setAlarm(context, action);
            }
            if ("set_timer".equals(type)) {
                return setTimer(context, action);
            }
            return new Result(false, "No reconozco esa acción del dispositivo.");
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e(TAG, "Android rechazó la acción solicitada: " + type, error);
            return new Result(false, "Android no ha permitido completar esa acción.");
        }
    }

    private static Result openApp(Context context, String requestedName) {
        String target = normalize(requestedName);
        if (target.isEmpty()) {
            return new Result(false, "No he entendido qué aplicación quieres abrir.");
        }

        PackageManager packageManager = context.getPackageManager();
        Intent launcherQuery = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launchableApps = packageManager.queryIntentActivities(launcherQuery, 0);
        ResolveInfo match = null;
        for (ResolveInfo app : launchableApps) {
            CharSequence label = app.loadLabel(packageManager);
            if (label != null && normalize(label.toString()).equals(target)) {
                match = app;
                break;
            }
        }
        if (match == null) {
            for (ResolveInfo app : launchableApps) {
                CharSequence label = app.loadLabel(packageManager);
                if (label != null && normalize(label.toString()).contains(target)) {
                    match = app;
                    break;
                }
            }
        }
        if (match == null || match.activityInfo == null) {
            return new Result(false, "No encuentro " + requestedName + " entre las aplicaciones instaladas.");
        }
        Intent launchIntent = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName);
        if (launchIntent == null) {
            return new Result(false, "Android no ofrece un acceso directo para " + requestedName + ".");
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(launchIntent);
            return new Result(true, "Abriendo " + match.loadLabel(packageManager) + " en el móvil.");
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e(TAG, "Android no pudo abrir " + requestedName + ".", error);
            return new Result(false, "Android no ha permitido abrir " + requestedName + ".");
        }
    }

    private static Result setAlarm(Context context, JSONObject action) {
        int hour = action.optInt("hour", -1);
        int minute = action.optInt("minute", -1);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return new Result(false, "No he entendido la hora de la alarma.");
        }
        Intent alarmIntent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, action.optString("label", "Sirvienta"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(alarmIntent);
            return new Result(
                    true,
                    String.format(Locale.getDefault(), "Alarma puesta a las %02d:%02d.", hour, minute));
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e(TAG, "No se pudo abrir la aplicación de alarmas.", error);
            return new Result(false, "No he podido abrir la aplicación de alarmas.");
        }
    }

    private static Result setTimer(Context context, JSONObject action) {
        int seconds = action.optInt("seconds", -1);
        if (seconds < 1 || seconds > 86_400) {
            return new Result(false, "El temporizador debe durar entre un segundo y 24 horas.");
        }
        Intent timerIntent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, action.optString("label", "Sirvienta"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(timerIntent);
            return new Result(true, "Temporizador iniciado en el móvil.");
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e(TAG, "No se pudo abrir la aplicación de temporizadores.", error);
            return new Result(false, "No he podido abrir la aplicación de temporizadores.");
        }
    }

    private static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
        String[] prefixes = {
                "la app de ", "la aplicacion de ", "app de ", "la app ", "app ",
                "el ", "la ", "los ", "las "
        };
        boolean changed;
        do {
            changed = false;
            for (String prefix : prefixes) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length()).trim();
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return normalized;
    }
}
