package com.upuaut.xposedsearch;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

public class RootUtils {

    private static final String TAG = "XposedSearch";

    /**
     * 检查是否有 Root 权限
     */
    public static boolean checkRootAccess() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su -c id");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            int exitCode = process.waitFor();
            return exitCode == 0 && line != null && line.contains("uid=0");
        } catch (Exception e) {
            Log.e(TAG, "checkRootAccess failed: " + e.getMessage());
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * 请求 Root 权限
     */
    public static boolean requestRootAccess() {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("id\n");
            os.writeBytes("exit\n");
            os.flush();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "requestRootAccess failed: " + e.getMessage());
            return false;
        } finally {
            try {
                if (os != null) os.close();
            } catch (Exception ignored) {}
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * 执行 Root 命令
     */
    public static CommandResult executeRootCommand(String... commands) {
        Process process = null;
        DataOutputStream os = null;
        BufferedReader successReader = null;
        BufferedReader errorReader = null;

        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());

            for (String command : commands) {
                os.writeBytes(command + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();

            int exitCode = process.waitFor();

            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();

            successReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = successReader.readLine()) != null) {
                output.append(line).append("\n");
            }

            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                error.append(line).append("\n");
            }

            return new CommandResult(exitCode == 0, output.toString().trim(), error.toString().trim());

        } catch (Exception e) {
            Log.e(TAG, "executeRootCommand failed: " + e.getMessage());
            return new CommandResult(false, "", e.getMessage());
        } finally {
            try {
                if (os != null) os.close();
                if (successReader != null) successReader.close();
                if (errorReader != null) errorReader.close();
            } catch (Exception ignored) {}
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * 强制停止应用
     */
    public static boolean forceStopApp(String packageName) {
        CommandResult result = executeRootCommand("am force-stop " + packageName);
        Log.d(TAG, "forceStopApp " + packageName + ": " + result.success);
        return result.success;
    }

    /**
     * 命令执行结果
     */
    public static class CommandResult {
        public final boolean success;
        public final String output;
        public final String error;

        public CommandResult(boolean success, String output, String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }
    }
}