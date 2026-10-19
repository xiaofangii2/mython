package com.xiaofang.mython;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class MyThon implements ModInitializer {
    public static final String MOD_ID = "mython";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static File pythonDir;
    private static File activePlatformFile;

    // 存储各平台的下载链接（这里用的是 python-build-standalone 和官方 embed 包）
    private static final Map<String, String> downloadUrls = new LinkedHashMap<>() {{
        put("linux-x86_64", "https://github.com/astral-sh/python-build-standalone/releases/download/20260804/cpython-3.14.6+20260804-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz");
        put("linux-aarch64", "https://github.com/astral-sh/python-build-standalone/releases/download/20260804/cpython-3.14.6+20260804-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz");
        put("windows-x86_64", "https://www.python.org/ftp/python/3.12.9/python-3.12.9-embed-amd64.zip");
        put("linux-system", "system");
    }};

    private static final Map<String, Thread> runningTasks = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        pythonDir = new File("config/mython/python");
        if (!pythonDir.exists()) pythonDir.mkdirs();
        activePlatformFile = new File(pythonDir, "active.txt");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerPythonCommand(dispatcher);
        });

        LOGGER.info("mython 已初始化，使用 /python help 查看指令");
    }

    private void registerPythonCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("python")
                .then(literal("help").executes(ctx -> sendHelp(ctx.getSource())))
                .then(literal("version").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("mython 1.0"), false);
                    return 1;
                }))
                .then(literal("set")
                        .executes(ctx -> listAvailable(ctx.getSource()))
                        .then(argument("platform", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    downloadUrls.keySet().forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> setPlatform(ctx, StringArgumentType.getString(ctx, "platform")))))
                .then(literal("py")
                        .then(argument("file", StringArgumentType.string())
                                .executes(ctx -> executeOnce(ctx, StringArgumentType.getString(ctx, "file")))
                                .then(literal("c")
                                        .executes(ctx -> executeRepeat(ctx, StringArgumentType.getString(ctx, "file"), 5))
                                        .then(argument("sec", IntegerArgumentType.integer(1))
                                                .executes(ctx -> executeRepeat(ctx, StringArgumentType.getString(ctx, "file"),
                                                        IntegerArgumentType.getInteger(ctx, "sec")))))))
                .executes(ctx -> sendHelp(ctx.getSource()))
        );
    }

    private int listAvailable(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("===== 可用的 Python 平台 ====="), false);
        String active = getActivePlatform();
        downloadUrls.forEach((name, url) -> {
            boolean isSystem = "system".equals(url);
            boolean extracted = isSystem || new File(pythonDir, name).exists();
            String status = extracted ? " [已就绪]" : " [未安装]";
            if (name.equals(active)) status += " [当前使用]";
            
            final String finalStatus = status;
            final String finalName = name;
            source.sendSuccess(() -> Component.literal(finalName + finalStatus), false);
        });
        source.sendSuccess(() -> Component.literal("使用 /python set <平台名> 联网下载并配置"), false);
        return 1;
    }

    private int setPlatform(CommandContext<CommandSourceStack> ctx, String platform) {
        CommandSourceStack source = ctx.getSource();
        if (!downloadUrls.containsKey(platform)) {
            source.sendFailure(Component.literal("未知平台: " + platform));
            return 0;
        }

        String url = downloadUrls.get(platform);
        if ("system".equals(url)) {
            saveActivePlatform(platform);
            source.sendSuccess(() -> Component.literal("已切换为系统自带 Python"), false);
            return 1;
        }

        File targetDir = new File(pythonDir, platform);
        if (targetDir.exists()) {
            saveActivePlatform(platform);
            source.sendSuccess(() -> Component.literal("平台已存在，已切换: " + platform), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("开始下载 " + platform + "，请稍候..."), false);

        // 开启异步线程下载，防止卡死服务器主线程
        new Thread(() -> {
            try {
                File archiveFile = new File(pythonDir, platform + ".archive");
                downloadFile(url, archiveFile);
                
                targetDir.mkdirs();
                if (url.endsWith(".zip")) {
                    unzip(archiveFile, targetDir);
                } else {
                    // 如果是 tar.gz，调用系统 tar 命令解压 (简单粗暴，不用引第三方库)
                    ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", archiveFile.getAbsolutePath(), "-C", targetDir.getAbsolutePath(), "--strip-components=1");
                    pb.start().waitFor();
                }
                archiveFile.delete();
                
                saveActivePlatform(platform);
                source.sendSuccess(() -> Component.literal("下载并解压成功: " + platform), true);
            } catch (Exception e) {
                source.sendFailure(Component.literal("下载或解压失败: " + e.getMessage()));
                LOGGER.error("下载失败", e);
            }
        }).start();

        return 1;
    }

    private void downloadFile(String urlStr, File dest) throws IOException {
        URL url = URI.create(urlStr).toURL();
        URLConnection conn = url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File outFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    outFile.setExecutable(true);
                }
            }
        }
    }

    private void saveActivePlatform(String platform) {
        try {
            Files.writeString(activePlatformFile.toPath(), platform, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("保存失败", e);
        }
    }

    private String getActivePlatform() {
        if (!activePlatformFile.exists()) return null;
        try {
            return Files.readString(activePlatformFile.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private File getActivePython() {
        String platform = getActivePlatform();
        if (platform == null) return null;
        
        String url = downloadUrls.get(platform);
        if ("system".equals(url)) {
            File sysPy = new File("/usr/bin/python3");
            if (sysPy.exists()) return sysPy;
            return new File("python3");
        }

        // python-build-standalone 解压后一般在 bin/python3 或 python.exe
        File binDir = new File(new File(pythonDir, platform), "bin");
        if (binDir.exists()) {
            File py = new File(binDir, "python3");
            if (py.exists()) return py;
        }
        
        File rootDir = new File(pythonDir, platform);
        File winExe = new File(rootDir, "python.exe");
        if (winExe.exists()) return winExe;
        
        return null;
    }

    private int sendHelp(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("===== mython 帮助 ====="), false);
        source.sendSuccess(() -> Component.literal("/python set - 联网下载并选择平台"), false);
        source.sendSuccess(() -> Component.literal("/python py <文件名.py> - 执行脚本"), false);
        source.sendSuccess(() -> Component.literal("/python py <文件名.py> c [秒] - 循环执行"), false);
        source.sendSuccess(() -> Component.literal("/python version - 版本"), false);
        return 1;
    }

    private int executeOnce(CommandContext<CommandSourceStack> ctx, String fileName) {
        CommandSourceStack source = ctx.getSource();
        File python = getActivePython();
        if (python == null) {
            source.sendFailure(Component.literal("未找到 Python，请先用 /python set 下载"));
            return 0;
        }
        File script = new File(pythonDir, fileName);
        if (!script.exists()) {
            source.sendFailure(Component.literal("找不到脚本: " + fileName));
            return 0;
        }
        runScript(source, python, script);
        return 1;
    }

    private int executeRepeat(CommandContext<CommandSourceStack> ctx, String fileName, int intervalSec) {
        CommandSourceStack source = ctx.getSource();
        File python = getActivePython();
        if (python == null) {
            source.sendFailure(Component.literal("未找到 Python"));
            return 0;
        }
        File script = new File(pythonDir, fileName);
        if (!script.exists()) {
            source.sendFailure(Component.literal("找不到脚本: " + fileName));
            return 0;
        }

        Thread existing = runningTasks.get(fileName);
        if (existing != null && existing.isAlive()) {
            existing.interrupt();
            runningTasks.remove(fileName);
            source.sendSuccess(() -> Component.literal("已停止循环: " + fileName), true);
            return 1;
        }

        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                runScript(source, python, script);
                try {
                    Thread.sleep(intervalSec * 1000L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        runningTasks.put(fileName, t);
        source.sendSuccess(() -> Component.literal("已开始循环: " + fileName), true);
        return 1;
    }

    private void runScript(CommandSourceStack source, File python, File script) {
        try {
            ProcessBuilder pb = new ProcessBuilder(python.getAbsolutePath(), "-u", script.getAbsolutePath());
            pb.directory(pythonDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String finalLine = line;
                    source.sendSuccess(() -> Component.literal(finalLine), false);
                }
            }
            process.waitFor();
        } catch (Exception e) {
            source.sendFailure(Component.literal("执行失败: " + e.getMessage()));
        }
    }
}
