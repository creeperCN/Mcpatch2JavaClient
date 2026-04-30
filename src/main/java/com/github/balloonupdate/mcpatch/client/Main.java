package com.github.balloonupdate.mcpatch.client;

import com.github.balloonupdate.mcpatch.client.config.AppConfig;
import com.github.balloonupdate.mcpatch.client.exceptions.McpatchBusinessException;
import com.github.balloonupdate.mcpatch.client.logging.ConsoleHandler;
import com.github.balloonupdate.mcpatch.client.logging.FileHandler;
import com.github.balloonupdate.mcpatch.client.logging.Log;
import com.github.balloonupdate.mcpatch.client.logging.LogLevel;
import com.github.balloonupdate.mcpatch.client.network.CloudConfigService;
import com.github.balloonupdate.mcpatch.client.ui.McPatchWindow;
import com.github.balloonupdate.mcpatch.client.utils.BytesUtils;
import com.github.balloonupdate.mcpatch.client.utils.DialogUtility;
import com.github.balloonupdate.mcpatch.client.utils.Env;
import com.github.kasuminova.GUI.SetupSwing;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.parser.ParserException;

import java.awt.*;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class Main {
    /**
     * 程序的启动方式
     */
    public enum StartMethod {
        /**
         * 作为独立进程启动
         */
        Standalone,

        /**
         * 作为 java agent lib 启动
         */
        JavaAgent,

        /**
         * 由 modloader 启动
         */
        ModLoader,
    }

    public static void main(String[] args) throws Throwable  {
        boolean graphicsMode = Desktop.isDesktopSupported();

        if (args.length > 0 && args[0].equals("windowless"))
            graphicsMode = false;

        AppMain(graphicsMode, StartMethod.Standalone, true, false);
    }

    public static void premain(String agentArgs, Instrumentation ins) throws Throwable  {
        boolean graphicsMode = Desktop.isDesktopSupported();

        if (agentArgs != null && agentArgs.equals("windowless"))
            graphicsMode = false;

        AppMain(graphicsMode, StartMethod.JavaAgent, true, false);
    }

    public static boolean modloader(boolean enableLogFile, boolean disableTheme) throws Throwable {
        boolean graphicsMode = Desktop.isDesktopSupported();

        return AppMain(graphicsMode, StartMethod.ModLoader, enableLogFile, disableTheme);
    }

    /**
     * McPatchClient主逻辑
     * @param graphicsMode 是否以图形模式启动（桌面环境通常以图形模式启动，安卓环境通常不以图形模式启动）
     * @param startMethod 程序的启动方式。
     * @param enableLogFile 是否写入日志文件
     * @param disableTheme 是否强制禁用主题
     * @return 有木有文件更新
     */
    static boolean AppMain(boolean graphicsMode, StartMethod startMethod, boolean enableLogFile, boolean disableTheme) throws Throwable {
        // 记录有无更新
        boolean hasUpdate = false;

        McPatchWindow window = null;

        try {
            // 初始化控制台日志系统
            InitConsoleLogging(graphicsMode, enableLogFile);

            // 准备各种目录
            Path progDir = getProgramDirectory();
            Path workDir = getWorkDirectory(progDir);

            // 读取本地配置文件（作为基础配置）
            Map<String, Object> localConfig = readConfig(progDir.resolve("mcpatch.yml"));

            // 如果配置了 cloud-server-url，尝试从云控后端拉取远程配置
            String cloudServerUrl = AppConfig.getString(localConfig, "cloud-server-url", null, "https://auth-config.mxzysoa.com");
            if (!cloudServerUrl.isEmpty()) {
                try {
                    Log.info("检测到云控配置，正在从云控后端拉取配置: " + cloudServerUrl);

                    String cloudApiKey = AppConfig.getString(localConfig, "cloud-api-key", null, "");
                    String cloudRsaPublicKey = AppConfig.getString(localConfig, "cloud-rsa-public-key", null, "");
                    int cloudTimeout = AppConfig.getInt(localConfig, "cloud-timeout", null, 10000);

                    // --- 从碎片合成完整密钥（XOR: key = frag1 XOR frag2 XOR frag3）---
                    String hmacSecret = assembleKeyFromFragments(
                        AppConfig.getString(localConfig, "cloud-hmac-frag1", null, ""),
                        AppConfig.getString(localConfig, "cloud-hmac-frag2", null, ""),
                        AppConfig.getString(localConfig, "cloud-hmac-frag3", null, "")
                    );
                    String aesKey = assembleKeyFromFragments(
                        AppConfig.getString(localConfig, "cloud-aes-frag1", null, ""),
                        AppConfig.getString(localConfig, "cloud-aes-frag2", null, ""),
                        AppConfig.getString(localConfig, "cloud-aes-frag3", null, "")
                    );

                    // 兼容旧配置：如果没配碎片，尝试读取完整密钥字段
                    if (hmacSecret.isEmpty()) {
                        hmacSecret = AppConfig.getString(localConfig, "cloud-hmac-secret", null, "");
                    }
                    if (aesKey.isEmpty()) {
                        aesKey = AppConfig.getString(localConfig, "cloud-aes-key", null, "");
                    }

                    CloudConfigService cloudService = new CloudConfigService(
                        cloudServerUrl, cloudApiKey, hmacSecret,
                        aesKey, cloudRsaPublicKey, cloudTimeout
                    );

                    String remoteYaml = cloudService.fetchConfig();

                    // 用云控返回的 YAML 替换本地配置内容
                    Yaml yaml = new Yaml();
                    Map<String, Object> remoteConfig = yaml.load(remoteYaml);

                    if (remoteConfig != null) {
                        localConfig = remoteConfig;
                        Log.info("云控配置拉取成功，已使用远程配置");
                    }
                } catch (Exception e) {
                    Log.warn("云控配置拉取失败，回退到本地配置: " + e.getMessage());
                }
            }

            AppConfig config = new AppConfig(localConfig);
            Path baseDir = getUpdateDirectory(workDir, config);

            // 初始化文件日志系统
            String logFileName = graphicsMode ? "mcpatch.log" : "mcpatch.log.txt";
            Path logFilePath = progDir.resolve(logFileName);

            if (enableLogFile)
                 InitFileLogging(logFilePath);

            // 非独立进程启动时，使用标签标明日志所属模块
            if (startMethod == StartMethod.ModLoader || startMethod == StartMethod.JavaAgent)
                Log.setAppIdentifier(true);

            // 打印调试信息
            PrintEnvironmentInfo(graphicsMode, startMethod, baseDir, workDir);

            // 应用主题
            if (graphicsMode && !disableTheme && !config.disableTheme)
                SetupSwing.init();

            // 初始化UI
            window = graphicsMode ? new McPatchWindow() : null;

            // 初始化窗口
            if (window != null) {
                window.setTitleText(config.windowTitle);
                window.setLabelText("正在连接到更新服务器");
                window.setLabelSecondaryText("");

                // 弹出窗口
                if (!config.silentMode)
                    window.show();
            }

//            // 点击窗口的叉时停止更新任务
//            if (window != null) {
//                window.onWindowClosing = w -> {
//                    if (workThread.isAlive())
//                        workThread.interrupt();
//                };
//            }

            Work work = new Work();
            work.window = window;
            work.config = config;
            work.baseDir = baseDir;
            work.progDir = progDir;
            work.logFilePath = logFilePath;
            work.graphicsMode = graphicsMode;
            work.startMethod = startMethod;

            try {
                // 启动更新任务
                hasUpdate = work.run();
            } catch (McpatchBusinessException e) {
                boolean a = e.getCause() instanceof InterruptedException;
                boolean b = e.getCause() instanceof ClosedByInterruptException;

                if (!a && !b) {
                    // 打印异常日志
                    try {
                        Log.openIndent("Crash");
                        Log.error(e.toString());
                        Log.closeIndent();
                    } catch (Exception ex) {
                        System.out.println("------------------------");
                        System.out.println(ex);
                    }

                    // 图形模式下弹框显示错误
                    if (graphicsMode) {
                        boolean sp = startMethod == StartMethod.Standalone;

                        String errMsg = e.getMessage() != null ? e.getMessage() : "<No Exception Message>";
                        String errMessage = BytesUtils.stringBreak(errMsg, 80, "\n");
                        String title = "发生错误 " + Env.getVersion();
                        String content = errMessage + "\n";
                        content += !sp ? "点击\"是\"显示错误详情并停止启动Minecraft，" : "点击\"是\"显示错误详情并退出，";
                        content += !sp ? "点击\"否\"继续启动Minecraft" : "点击\"否\"直接退出程序";

                        boolean choice = DialogUtility.confirm(title, content);

                        if (!sp)
                        {
                            if (choice)
                            {
                                DialogUtility.error("错误详情 " + Env.getVersion(), e.toString());

                                throw e;
                            }
                        } else {
                            if (choice)
                                DialogUtility.error("错误详情 " + Env.getVersion(), e.toString());

                            throw e;
                        }
                    }
                } else {
                    Log.info("更新过程被用户打断！");
                }
            }
        } finally {
            if (window != null)
                window.destroy();

            if (startMethod != Main.StartMethod.Standalone)
                Log.info("continue to start Minecraft!");

            // if (startMethod == StartMethod.Standalone)
            //     Runtime.getRuntime().exit(0);
        }

        return hasUpdate;
    }

    /**
     * 获取Jar文件所在的目录
     */
    static Path getProgramDirectory()
    {
        if (Env.isDevelopment()) {
            String devWorkDir = System.getenv("MCPATCH_DEV_WORK_DIR");
            String devProgDir = System.getenv("MCPATCH_DEV_PROG_DIR");

            // 优先用环境变量里的
            if (devWorkDir != null && devProgDir != null) {
                return Paths.get(devProgDir);
            }

            // 然后用test文件夹
            Path userDir = Paths.get(System.getProperty("user.dir"));
            return userDir.resolve("test");
        }

        return Env.getJarPath().getParent();
    }

    /**
     * 获取进程的工作目录
     */
    static Path getWorkDirectory(Path progDir) {
        Path userDir = Paths.get(System.getProperty("user.dir"));

        if (Env.isDevelopment()) {
            String devWorkDir = System.getenv("MCPATCH_DEV_WORK_DIR");
            String devProgDir = System.getenv("MCPATCH_DEV_PROG_DIR");

            Path workDir;

            // 优先用环境变量里的
            if (devWorkDir != null && devProgDir != null) {
                workDir = Paths.get(devWorkDir);
            } else {
                // 同程序文件夹
                workDir = progDir;
            }

            try {
                Files.createDirectories(workDir);
                return workDir;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return userDir;
    }

    /**
     * 获取需要更新的起始目录
     * @param workDir 工作目录
     * @param config 配置信息
     * @return 更新起始目录
     * @throws McpatchBusinessException 当智能搜索搜不到.minecraft目录时
     */
    static Path getUpdateDirectory(Path workDir, AppConfig config) throws McpatchBusinessException {
        // 开发环境下直接返回工作目录
        if (Env.isDevelopment())
            return workDir;

        // 如果填写了base-path，就使用
        if (!config.basePath.equals("")) {
            return Env.getJarPath().getParent().resolve(config.basePath);
        }

        // 如果没有填写，就智能搜索
        Path result = searchDotMinecraft(workDir);

        // 必须找到才可以
        if (result == null) {
            String text = "找不到.minecraft目录。" +
                    "请将软件放到.minecraft目录的同级或者.minecraft目录下（最大7层深度）然后再次尝试运行。" +
                    "Windows系统下请不要使用右键的“打开方式”选择Java运行，而是要将Java设置成默认打开方式然后双击打开";
            throw new McpatchBusinessException(text);
        }

        return result;
    }

    /**
     * 向上搜索，直到有一个父目录包含 .minecraft 目录
     */
    static Path searchDotMinecraft(Path basedir) {
        try {
            File d = basedir.toFile();

            for (int i = 0; i < 7; i++) {
                for (File f : d.listFiles()) {
                    if (f.getName().equals(".minecraft")) {
                        return d.toPath();
                    }
                }

                d = d.getParentFile();
            }
        } catch (NullPointerException e) {
            return null;
        }

        return null;
    }

    // 从外部/内部读取配置文件并将内容返回
    static Map<String, Object> readConfig(Path external) throws McpatchBusinessException {
        try {
            Map<String, Object> result;

            Yaml yaml = new Yaml();

            // 如果外部配置文件存在，优先使用
            if (Files.exists(external)) {
                result = yaml.load(new String(Files.readAllBytes(external)));
            }

            // 如果内部配置文件存在，则读取内部的
            else {
                // 开发时必须要有外部配置文件
                if (Env.isDevelopment()) {
                    throw new McpatchBusinessException("找不到配置文件: mcpatch.yml，开发时必须要有配置文件");
                }

                // 读取内部配置文件
                try (JarFile jar = new JarFile(Env.getJarPath().toFile())) {
                    ZipEntry entry = jar.getJarEntry("mcpatch.yml");

                    try (InputStream stream = jar.getInputStream(entry)) {
                        result = yaml.load(stream);
                    }
                }
            }

//            System.out.println(result);

            return result;
//
//            if (content.startsWith(":")) {
//                try {
//                    content = new String(Base64.getDecoder().decode(content.substring(1)));
//                } catch (IllegalArgumentException e) {
//                    throw new InvalidConfigFileException();
//                }
//            }
        } catch (ParserException | IOException e) {
            throw new McpatchBusinessException(e);
        }
    }

    /**
     * 初始化控制台日志系统
     */
    static void InitConsoleLogging(boolean graphicsMode, boolean enableLogFile) {
        LogLevel level;

        if (Env.isDevelopment()) {
            // 图形模式或者说禁用了日志文件，这时console就应该显示更详细的日志
            if (graphicsMode || !enableLogFile) {
                level = LogLevel.Debug;
            } else {
                level = LogLevel.Info;
            }
        } else {
            // 打包后也要显示详细一点的日志
            level = LogLevel.Debug;
        }

        Log.addHandler(new ConsoleHandler(level));
    }

    /**
     * 初始化文件日志系统
     */
    static void InitFileLogging(Path logFilePath) {
        Log.addHandler(new FileHandler(LogLevel.All, logFilePath));
    }

    /**
     * 收集并打印环境信息
     */
    static void PrintEnvironmentInfo(boolean graphicsMode, StartMethod startMethod, Path baseDir, Path workDir) {
        String jvmVersion = System.getProperty("java.version");
        String jvmVendor = System.getProperty("java.vendor");
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        String osVersion = System.getProperty("os.version");

        Log.info("已用内存: " + BytesUtils.convertBytes(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
        Log.info("图形模式: " + graphicsMode);
        Log.info("启动方式: " + startMethod);
        Log.info("基本目录: " + baseDir);
        Log.info("工作目录: " + workDir);
        Log.info("二进制文件目录: " + (Env.isDevelopment() ? "Dev" : Env.getJarPath()));
        Log.info("软件版本: " + Env.getVersion() + " (" + Env.getGitCommit() + ")");
        Log.info("虚拟机版本: " + jvmVendor + " (" + jvmVersion + ")");
        Log.info("操作系统: " + osName + ", " + osVersion + ", " + osArch);
    }

    // ============ 密钥碎片化：XOR 合成 ============

    /**
     * 从 3 个十六进制碎片合成完整密钥
     * 原理: original = frag1 XOR frag2 XOR frag3
     *
     * @param frag1Hex 碎片1的十六进制字符串
     * @param frag2Hex 碎片2的十六进制字符串
     * @param frag3Hex 碎片3的十六进制字符串
     * @return 合成后的完整密钥十六进制字符串，如果碎片为空则返回空字符串
     */
    static String assembleKeyFromFragments(String frag1Hex, String frag2Hex, String frag3Hex) {
        // 任一碎片为空则无法合成
        if (frag1Hex == null || frag1Hex.isEmpty()
            || frag2Hex == null || frag2Hex.isEmpty()
            || frag3Hex == null || frag3Hex.isEmpty()) {
            return "";
        }

        // 验证十六进制格式
        frag1Hex = frag1Hex.trim();
        frag2Hex = frag2Hex.trim();
        frag3Hex = frag3Hex.trim();

        if (!frag1Hex.matches("[0-9a-fA-F]+") || !frag2Hex.matches("[0-9a-fA-F]+") || !frag3Hex.matches("[0-9a-fA-F]+")) {
            Log.warn("密钥碎片包含非十六进制字符，无法合成");
            return "";
        }

        if (frag1Hex.length() != frag2Hex.length() || frag2Hex.length() != frag3Hex.length()) {
            Log.warn("密钥碎片长度不一致（" + frag1Hex.length() + ", " + frag2Hex.length() + ", " + frag3Hex.length() + "），无法合成");
            return "";
        }

        int len = frag1Hex.length() / 2;
        StringBuilder result = new StringBuilder(len * 2);

        for (int i = 0; i < len; i++) {
            int b1 = Integer.parseInt(frag1Hex.substring(i * 2, i * 2 + 2), 16);
            int b2 = Integer.parseInt(frag2Hex.substring(i * 2, i * 2 + 2), 16);
            int b3 = Integer.parseInt(frag3Hex.substring(i * 2, i * 2 + 2), 16);
            int original = b1 ^ b2 ^ b3;
            result.append(String.format("%02x", original));
        }

        Log.info("密钥碎片合成成功（" + len + " 字节）");
        return result.toString();
    }
}
