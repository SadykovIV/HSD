package com.hsd.universal.dex;

import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.Keep;
import com.hsd.universal.carplay.BluetoothIdentity;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Entry point invoked by an authorized ADB shell, not an Android component or app service. */
@Keep
public final class ManualAccessLauncher {
    private static final int COMMAND_LIMIT = 16_384;
    private static final String STATUS = "files/computer-access-status.json";
    private static final String END = "\u0000";
    private static final String OVERFLOW = "\u0000OVERFLOW";
    private static final Pattern UID_LINE = Pattern.compile("^package:" + Pattern.quote(ManualAccessPolicy.PACKAGE) + "\\s+uid:(\\d+)\\s*$", Pattern.MULTILINE);

    private ManualAccessLauncher() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Same bounded activation used by the explicitly requested, detached phone bootstrap. */
    public static int run(String[] args) {
        return run(args, null);
    }

    /** Private receipt identity follows this phone bootstrap into its new app process. */
    static int runPhone(String[] args, String phoneRequestToken) {
        if (!PhoneActivationPolicy.validToken(phoneRequestToken)) throw new IllegalArgumentException("Invalid phone activation request");
        return run(args, phoneRequestToken);
    }

    private static int run(String[] args, String phoneRequestToken) {
        int result = 0;
        try {
            boolean savedOptions = args.length == 1 && "--saved".equals(args[0]);
            ManualAccessPolicy.Options options = ManualAccessPolicy.Options.parse(savedOptions ? new String[0] : args);
            boolean deskAa = ManualAccessPolicy.ANDROID_AUTO;
            if (options.help) {
                if (deskAa) {
                    System.out.println("HSD [--audio] [--screen]\n--audio передаёт звук приложений на магнитолу по выбранному подключению. --screen включает выбранное управление панелью телефона.");
                    return 0;
                }
                System.out.println(ManualAccessPolicy.APP_NAME + " ADB activation: [--audio] [--screen]\n"
                    + "Основной доступ включает клавиатуру на экранах " + ManualAccessPolicy.APP_NAME + " и управление текущей музыкой: пауза при отключении USB и продолжение той же сессии после готовности CarLife.\n"
                    + "На новой установке USB-звук включён, управление дисплеем выключено. Для USB-звука передайте --audio, для выбранного в настройках управления панелью — --screen.\n"
                    + "Служебные API доступны только в активированном процессе " + ManualAccessPolicy.APP_NAME + ".");
                return 0;
            }
            check(Process.myUid() == 2000, "Запустите команду из обычного ADB shell (UID 2000), без root.");
            check(Build.VERSION.SDK_INT >= 29, "Нужен Android 10 или новее.");
            // A shell-owned, non-symlink lock serializes two manual invocations. No keys or IDs are stored.
            try (FileChannel channel = FileChannel.open(Paths.get(deskAa ? "/data/local/tmp/hsd-universal-manual-access.lock" : "/data/local/tmp/hsd-manual-access.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = channel.tryLock()) {
                check(lock != null, "Другая ручная активация ещё выполняется. Дождитесь её завершения.");
                int uid = packageUid();
                String runAsUid = run(5000, "run-as", ManualAccessPolicy.PACKAGE, "--user", Integer.toString(uid / 100000), "id", "-u").trim();
                check(runAsUid.equals(Integer.toString(uid)), "Android не предоставил доступ к данным " + ManualAccessPolicy.APP_NAME + " через run-as. Проверьте, что команда активации запущена для установленного APK и текущего пользователя Android.");
                if (savedOptions) {
                    byte[] prefs = readPreferences(Integer.toString(uid / 100000), "hsd_dex");
                    options = new ManualAccessPolicy.Options(ActivationPreferences.read(prefs, "desktop_usb_playback_audio", true),
                        ActivationPreferences.read(prefs, "desktop_panel_control", false), false);
                }
                boolean newActivation = activate(uid, options, phoneRequestToken);
                rememberControllerAddress(uid);
                if (newActivation) {
                    try {
                        String opened = run(5000, "am", "start", "--user", "current", "--display", "0", "-n", ManualAccessPolicy.ACTIVITY);
                        if (opened.contains("Error:") || opened.contains("Exception")) throw new IOException("Activity launch rejected");
                    } catch (Exception ignored) {
                        System.out.println("Доступ включён. Откройте " + ManualAccessPolicy.APP_NAME + " на телефоне вручную.");
                    }
                }
                System.out.println(ManualAccessPolicy.READY + "\nПрава и текущая служба рабочего стола готовы.\n"
                    + (Build.VERSION.SDK_INT >= 37
                        ? "Для автоматического восстановления после переподключения" + (deskAa ? " магнитолы" : " USB") + " завершите подготовку в " + ManualAccessPolicy.APP_NAME + " → Настройки → Активация. Готовность текущей службы не подтверждает готовность её восстановления.\n"
                        : "Можно отключить компьютер и подключить телефон к " + (deskAa ? "выбранной магнитоле" : "CarLife") + ".\n")
                    + "Повторная активация нужна после перезагрузки, обновления, принудительной остановки или потери доступа.");
            }
        } catch (Exception error) {
            result = 1;
            System.err.println("Активация не завершена: " + safe(error.getMessage())
                + "\n" + ManualAccessPolicy.APP_NAME + " не останавливался принудительно. Проверьте настройку и повторите команду.");
        }
        return result;
    }

    /** Android may withhold its address from ordinary apps. The already-authorized activation
     * shell can hand it to this installation. Keep the value out of logs, argv and backups. */
    private static void rememberControllerAddress(int uid) {
        String user = Integer.toString(uid / 100000);
        java.lang.Process writer = null;
        try {
            String raw = new String(readCommand(3000, 128, true, "/system/bin/settings", "--user",
                user, "get", "secure", "bluetooth_address"), StandardCharsets.US_ASCII);
            String address = BluetoothIdentity.normalize(raw);
            if (address == null) return;
            writer = new ProcessBuilder("run-as", ManualAccessPolicy.PACKAGE, "--user", user, "sh", "-c",
                "umask 077; mkdir -p no_backup && f=$(mktemp no_backup/.carplay-address.XXXXXX) && " +
                "cat > \"$f\" && mv -f \"$f\" no_backup/" + BluetoothIdentity.BASENAME).start();
            try (java.io.OutputStream out = writer.getOutputStream()) { out.write(address.getBytes(StandardCharsets.US_ASCII)); }
            writer.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { /* Optional; a missing controller must not block activation. */ }
        finally { if (writer != null && writer.isAlive()) writer.destroyForcibly(); }
    }

    private static int packageUid() throws Exception {
        String paths = run(5000, "pm", "path", ManualAccessPolicy.PACKAGE);
        check(paths.startsWith("package:/"), "Для активации установите " + ManualAccessPolicy.PACKAGE + ".");
        String user = run(5000, "am", "get-current-user").trim();
        check(user.matches("[0-9]+"), "Не удалось определить активного пользователя Android.");
        String packages = run(5000, "cmd", "package", "list", "packages", "-U", "--user", user, ManualAccessPolicy.PACKAGE);
        Matcher match = UID_LINE.matcher(packages);
        check(match.find(), "Не удалось проверить UID " + ManualAccessPolicy.APP_NAME + ".");
        int uid = Integer.parseInt(match.group(1));
        check(!match.find() && uid / 100000 == Integer.parseInt(user) && uid % 100000 >= 10000, "Неоднозначный UID " + ManualAccessPolicy.APP_NAME + ".");
        return uid;
    }

    private static boolean activate(int uid, ManualAccessPolicy.Options options, String phoneRequestToken) throws Exception {
        JSONObject active = heartbeat(uid, null);
        if (active != null) {
            active = ensureKeyboard(uid, active);
            requireCapabilities(active, options);
            ensureIndependentDisplay(uid, active);
            System.out.println("Доступ работает в текущем сеансе.");
            return false;
        }
        // A known live instrumentation with a delayed heartbeat must not be replaced under an active projection.
        check(liveRecovery(uid) == null, "Действующий сеанс пока не подтвердил состояние. Дождитесь его ответа; новая активация не запущена.");
        if (!options.audio && !options.screen && hudApiRequested(uid)) options = options.withHudApi();
        if (options.audio) System.out.println(ManualAccessPolicy.ANDROID_AUTO
            ? "Запрошена передача музыки и подсказок на магнитолу по выбранному подключению."
            : "Запрошен временный маршрут USB-звука: музыка и подсказки в CarLife, без микрофона.");
        if (options.screen) System.out.println("Запрошен временный доступ к панели и системной диагностике (DUMP) для чтения времени активности экрана телефона. Перед выключением панели " + ManualAccessPolicy.APP_NAME + " откроет свой экран для двойного касания.");
        String token = phoneRequestToken != null ? phoneRequestToken : DisplayHelperRendezvous.newToken();
        try { launchDisplayHelper(uid, token, options.screen && DelegatedPanelAccess.INSTANCE.usesShellBackend(), null); }
        catch (Exception error) {
            System.err.println("Не удалось запустить службу клавиатуры на магнитоле: " + error.getClass().getSimpleName());
        }
        java.lang.Process launcher = new ProcessBuilder("/system/bin/sh", "-c", ManualAccessPolicy.launchCommand(token, options))
            .redirectErrorStream(true).start();
        ArrayBlockingQueue<String> lines = new ArrayBlockingQueue<>(128);
        Thread reader = new Thread(() -> readLines(launcher.getInputStream(), lines), com.hsd.universal.BuildConfig.BRAND + "-manual-output");
        reader.setDaemon(true); reader.start();
        ManualAccessPolicy.ReadyOutput output = new ManualAccessPolicy.ReadyOutput();
        boolean launcherStopped = false;
        try {
            long deadline = SystemClock.elapsedRealtime() + 30_000;
            while (!output.ready) {
                long left = deadline - SystemClock.elapsedRealtime();
                check(left > 0, "За 30 секунд " + ManualAccessPolicy.APP_NAME + " не подтвердил доступ. Проверьте выбранные опции в приложении.");
                String line = lines.poll(left, TimeUnit.MILLISECONDS);
                check(line != null, "За 30 секунд " + ManualAccessPolicy.APP_NAME + " не подтвердил доступ.");
                check(!END.equals(line), "Процесс запуска завершился без READY.");
                check(!OVERFLOW.equals(line), "Слишком большой ответ процесса запуска.");
                output.feed(line);
                check(output.failure == null, output.failure);
            }
            check(output.pid != null, "Нет PID собственного процесса запуска: завершение запрещено.");
            stopOwnedLauncher(output.pid, token, options);
            launcherStopped = true;
            launcher.waitFor(3, TimeUnit.SECONDS);
            long verifyAfter = SystemClock.elapsedRealtime();
            requestFreshHeartbeat(uid);
            System.out.println("Проверяю доступ после завершения собственного ADB-запуска…");
            long heartbeatDeadline = SystemClock.elapsedRealtime() + 12_000;
            while (SystemClock.elapsedRealtime() < heartbeatDeadline) {
                JSONObject status = heartbeat(uid, verifyAfter);
                if (status != null) {
                    status = ensureKeyboard(uid, status);
                    requireCapabilities(status, options);
                    ensureIndependentDisplay(uid, status);
                    return true;
                }
                Thread.sleep(500);
            }
            throw new IOException("Нет свежего подтверждения доступа после завершения процесса запуска.");
        } finally {
            if (!launcherStopped && output.pid != null && launcher.isAlive()) {
                try { stopOwnedLauncher(output.pid, token, options); }
                catch (Exception ignored) {
                    if (launcher.isAlive()) System.err.println("Процесс запуска не завершён: его владелец не подтверждён.");
                }
            }
            // Never force-stop the app, kill by process name, or destroy an unverified launcher PID.
            try { launcher.getInputStream().close(); } catch (IOException ignored) { }
        }
    }

    private static void launchDisplayHelper(int uid, String token, boolean panel, DisplayHelperRendezvous owner) throws Exception {
        String paths = run(5000, "pm", "path", ManualAccessPolicy.PACKAGE);
        String apk = null;
        for (String line : paths.split("\\r?\\n")) {
            if (line.startsWith("package:/data/app/") && line.endsWith("/base.apk")) {
                check(apk == null, "Неоднозначный путь приложения");
                apk = line.substring("package:".length());
            }
        }
        check(apk != null && new File(apk).isFile(), "Не найден установленный APK");
        String[] command = ShellImeSupervisor.command(uid, token, panel);
        if (owner != null) { // The first recovery worker must pin the old owner just like subsequent supervised workers.
            command = java.util.Arrays.copyOf(command, command.length + 2);
            command[command.length - 2] = Integer.toString(owner.pid);
            command[command.length - 1] = Long.toString(owner.processTicks);
        }
        ProcessBuilder helper = new ProcessBuilder(command);
        helper.environment().put("CLASSPATH", apk);
        File devNull = new File("/dev/null");
        helper.redirectInput(devNull).redirectOutput(devNull).redirectError(devNull).start();
    }

    private static boolean hudApiRequested(int uid) {
        if (Build.VERSION.SDK_INT < 36) return false;
        String user = Integer.toString(uid / 100000);
        try {
            String enabled = run(3000, "settings", "--user", user, "get", "secure", "enabled_notification_listeners").trim();
            String component = ManualAccessPolicy.PACKAGE + "/";
            boolean approved = java.util.Arrays.stream(enabled.split(":")).anyMatch(s ->
                s.equals(component + ".cluster.ClusterNavigationListener") ||
                s.equals(component + ManualAccessPolicy.PACKAGE + ".cluster.ClusterNavigationListener"));
            if (!approved) return false;
            return ActivationPreferences.hudEnabled(readPreferences(user, "aa"));
        } catch (Exception unavailable) { return false; }
    }

    private static byte[] readPreferences(String user, String name) throws Exception {
        // SharedPreferences restores the backup first after an interrupted write.
        return readCommand(3000, ActivationPreferences.MAX_BYTES, true,
            "run-as", ManualAccessPolicy.PACKAGE, "--user", user, "sh", "-c",
            "if [ ! -e shared_prefs ]; then printf '<map/>'; exit; fi; " +
            "[ -d shared_prefs ] && [ -x shared_prefs ] || exit 1; " +
            "f=$1; if [ -e \"$f.bak\" ]; then f=$f.bak; fi; " +
            "if [ -f \"$f\" ]; then exec cat \"$f\"; fi; [ ! -e \"$f\" ] && printf '<map/>'",
            "sh", "shared_prefs/" + name + ".xml");
    }

    private static DisplayHelperRendezvous liveRecovery(int uid) {
        try {
            String raw = run(3000, "run-as", ManualAccessPolicy.PACKAGE, "--user", Integer.toString(uid / 100000),
                    "cat", DisplayHelperRendezvous.RUN_AS_PATH);
            DisplayHelperRendezvous record = DisplayHelperRendezvous.parse(raw);
            if (record == null || record.uid != uid || record.elapsed > SystemClock.elapsedRealtime()) return null;
            int boot = Integer.parseInt(run(3000, "settings", "get", "global", "boot_count").trim());
            return record.matchesOwner(uid, identity(record.pid), boot) ? record : null;
        } catch (Exception unavailable) { return null; } // Never echo the private record.
    }

    private static JSONObject ensureKeyboard(int uid, JSONObject status) throws Exception {
        if (displayAccessReady(status)) return status;
        DisplayHelperRendezvous record = liveRecovery(uid);
        check(record != null && record.matchesHeartbeat(number(status.opt("pid")), number(status.opt("elapsedRealtimeMs")),
                status.optString("helperSession")), "Не удалось подтвердить активный сеанс клавиатуры. Обновите или заново активируйте " + ManualAccessPolicy.APP_NAME + ".");
        System.out.println("Восстанавливаю клавиатуру текущего сеанса…");
        long launched = SystemClock.elapsedRealtime();
        // A live helper may be waiting for the next retry on a newly registered display.
        // Only launch a replacement if its authenticated connection has actually gone.
        if (!Boolean.TRUE.equals(status.opt("displayHelper"))) launchDisplayHelper(uid, record.token, record.panel, record);
        requestFreshHeartbeat(uid);
        long deadline = launched + 18_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject current = heartbeat(uid, launched);
            if (current != null) {
                check(record.matchesHeartbeat(number(current.opt("pid")), number(current.opt("elapsedRealtimeMs")),
                        current.optString("helperSession")), "Активный сеанс изменился во время восстановления клавиатуры.");
                if (displayAccessReady(current)) {
                    DisplayHelperRendezvous live = liveRecovery(uid);
                    check(live != null && record.fingerprint().equals(live.fingerprint()), "Сеанс клавиатуры уже завершён.");
                    return current;
                }
            }
            Thread.sleep(400);
        }
        throw new IOException("Действующий сеанс не подтвердил восстановление клавиатуры. Существующая активация не изменена.");
    }

    private static boolean displayAccessReady(JSONObject status) {
        return Boolean.TRUE.equals(status.opt("keyboardRouting")) &&
            (!Boolean.TRUE.equals(status.opt("panelNeedsHelper")) || Boolean.TRUE.equals(status.opt("displayHelper")));
    }

    private static void ensureIndependentDisplay(int uid, JSONObject status) throws Exception {
        if (Build.VERSION.SDK_INT < 37 || Boolean.FALSE.equals(status.opt("needsIndependentHost"))) return;
        DisplayHelperRendezvous record = liveRecovery(uid);
        check(record != null && record.matchesHeartbeat(number(status.opt("pid")), number(status.opt("elapsedRealtimeMs")),
            status.optString("helperSession")), "Не удалось подтвердить владельца независимого экрана. Проверьте состояние доступа в приложении.");
        System.out.println("Подготавливаю независимый экран Android 17…");
        VdmHostBootstrap.ensureReady(uid, record.pid, record.token, null);
    }

    private static void requireCapabilities(JSONObject status, ManualAccessPolicy.Options options) throws IOException {
        check(Build.VERSION.SDK_INT < 33 || Boolean.TRUE.equals(status.opt("mediaControl")),
            "Работает предыдущая активация без управления музыкой. Завершите доступ в " + ManualAccessPolicy.APP_NAME + " и активируйте его снова. Текущие права не изменены.");
        boolean audioUnavailable = Boolean.TRUE.equals(status.opt("audioUnsupported"));
        if (options.audio && audioUnavailable) System.out.println("Android не предоставил захват звука. Для музыки выберите Bluetooth на магнитоле.");
        check(!options.audio || audioUnavailable || Boolean.TRUE.equals(status.opt("playbackAudio")),
            "Доступ уже работает без USB-звука. Включите USB-звук в " + ManualAccessPolicy.APP_NAME + ", завершите доступ в приложении и повторите команду с --audio.");
        if (options.screen && !Boolean.TRUE.equals(status.opt("panelControl"))) {
            check(Boolean.TRUE.equals(status.opt("panelUnsupported")),
                "Доступ уже работает без управления экраном. Включите эту опцию в " + ManualAccessPolicy.APP_NAME + ", завершите доступ в приложении и повторите команду с --screen.");
            System.out.println(PhoneActivationPolicy.PANEL_UNAVAILABLE + "\n" + PhoneActivationPolicy.PANEL_MESSAGE);
        }
    }

    // Ask the existing activation to recheck its own grants now. The request itself is
    // never evidence of readiness. The periodic heartbeat also covers lost filesystem events.
    private static void requestFreshHeartbeat(int uid) throws InterruptedException {
        try {
            run(1500, "run-as", ManualAccessPolicy.PACKAGE, "--user", Integer.toString(uid / 100000),
                "sh", "-c", ": > files/" + ManualAccessPolicy.STATUS_REFRESH);
        } catch (InterruptedException e) { throw e; }
        catch (Exception ignored) { /* Periodic verification remains available. */ }
    }

    private static JSONObject heartbeat(int uid, Long after) {
        try {
            String raw = run(3000, "run-as", ManualAccessPolicy.PACKAGE, "--user", Integer.toString(uid / 100000), "cat", STATUS);
            if (raw.length() > 4096) return null;
            JSONObject status = new JSONObject(raw);
            if (number(status.opt("protocol")) != 1) return null;
            long pid = number(status.opt("pid")), elapsed = number(status.opt("elapsedRealtimeMs"));
            if (pid > Integer.MAX_VALUE || pid <= 1 || !ManualAccessPolicy.freshHeartbeat(
                    Boolean.TRUE.equals(status.opt("ready")), Boolean.TRUE.equals(status.opt("permissionsChecked")),
                    (int) pid, elapsed, SystemClock.elapsedRealtime(), after)) return null;
            ManualAccessPolicy.Identity identity = identity((int) pid);
            for (long owner : identity.uids) if (owner != uid) return null;
            if (!identity.argv.get(0).equals(ManualAccessPolicy.PACKAGE)) return null;
            return status;
        } catch (Exception ignored) { return null; }
    }

    private static long number(Object value) {
        return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : -1;
    }

    private static ManualAccessPolicy.Identity identity(int pid) throws Exception {
        check(pid > 1, "Некорректный PID.");
        String path = "/proc/" + pid + "/";
        return ManualAccessPolicy.parseIdentity(pid, new String(readFile(path + "status", 16_384), StandardCharsets.UTF_8),
            readFile(path + "cmdline", 16_384), new String(readFile(path + "stat", 8192), StandardCharsets.UTF_8));
    }

    private static byte[] readFile(String path, int maximum) throws IOException {
        try (InputStream in = new FileInputStream(path)) { return boundedRead(in, maximum); }
    }

    private static void stopOwnedLauncher(int pid, String token, ManualAccessPolicy.Options options) throws Exception {
        ManualAccessPolicy.Identity owner = identity(pid);
        check(ManualAccessPolicy.ownsLauncher(owner, token, options), "UID/аргументы собственного процесса запуска не совпали; завершение запрещено.");
        run(5000, "/system/bin/sh", "-c", ManualAccessPolicy.guardedKillCommand(owner, token, options));
    }

    private static String run(long timeoutMs, String... command) throws Exception {
        return new String(readCommand(timeoutMs, COMMAND_LIMIT, false, command), StandardCharsets.UTF_8);
    }

    private static byte[] readCommand(long timeoutMs, int limit, boolean privateOutput, String... command) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final byte[][] bytes = new byte[1][];
        final Exception[] failure = new Exception[1];
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) { bytes[0] = boundedRead(input, limit); }
            catch (Exception error) { failure[0] = error; }
        }, com.hsd.universal.BuildConfig.BRAND + "-manual-command");
        reader.setDaemon(true); reader.start();
        // These are only short, directly owned inspection/guard commands, never the instrumentation.
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Команда Android не ответила вовремя: " + command[0]);
        }
        reader.join(1000);
        check(!reader.isAlive(), "Не завершилось чтение ответа Android.");
        if (failure[0] != null) throw failure[0];
        byte[] output = bytes[0] == null ? new byte[0] : bytes[0];
        if (process.exitValue() != 0) throw new IOException(privateOutput ? "Не удалось прочитать настройки " + ManualAccessPolicy.APP_NAME + "."
            : "Android " + command[0] + ": " + safe(new String(output, StandardCharsets.UTF_8)));
        return output;
    }

    private static byte[] boundedRead(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > maximum) throw new IOException("Ответ Android превысил лимит.");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static void readLines(InputStream in, ArrayBlockingQueue<String> target) {
        try {
            ByteArrayOutputStream line = new ByteArrayOutputStream(); int value;
            while ((value = in.read()) != -1) {
                if (value == '\n') {
                    if (!target.offer(new String(line.toByteArray(), StandardCharsets.UTF_8))) throw new IOException("Output queue full");
                    line.reset();
                } else {
                    if (line.size() >= 8192) throw new IOException("Output line too large");
                    line.write(value);
                }
            }
            if (line.size() > 0) target.offer(new String(line.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception error) { target.clear(); target.offer(OVERFLOW); }
        finally { target.offer(END); }
    }

    private static void check(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message == null ? "Неизвестная ошибка активации" : message);
    }

    private static String safe(String text) {
        if (text == null || text.isEmpty()) return "ошибка Android";
        String clean = text.replaceAll("(?i)\\b[0-9a-f]{32,}\\b", "[скрыто]")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "?").trim();
        return clean.length() > 600 ? clean.substring(clean.length() - 600) : clean;
    }
}
