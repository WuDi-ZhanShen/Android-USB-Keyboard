package com.emu.anyUSB;

import android.app.IApplicationThread;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Scanner;

public class USBLocalSocket {


    static FileOutputStream device;
    static FileObserver observer;
    static File hidFile;
    static boolean hidFileIsValid;

    public static void main(String[] args) {


        //check if user has Root permission
        int uid = android.os.Process.myUid();
        if (uid != 0) {
            System.err.printf("Requires Root! You are %d now\n", uid);
            System.exit(255);
        }

        // 创建FileObserver对象，并指定要监测的目录
        observer = new FileObserver("/dev/") {
            @Override
            public void onEvent(int event, String path) {
                if (path.equals(hidFile.getName())) {
                    if (event == 512) {
                        hidFileIsValid = false;
                        try {
                            device.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        return;
                    }
                    if (event == 256) {
                        hidFileIsValid = true;
                        try {
                            device = new FileOutputStream(hidFile, true);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }

            }
        };


        //JVM异常关闭时的处理程序
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                observer.stopWatching();
                try {
                    device.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });


        //看看hidg*文件是否存在
        File file = new File("/dev/");
        if (!file.exists() || !file.isDirectory()) {
            System.err.println("Error finding /dev/!\n");
            System.exit(255);
            return;
        }
        hidFile = null;
        for (File tmp : file.listFiles()) {
            if (tmp.getName().startsWith("hidg")) {
                hidFile = tmp;
                break;
            }
        }
        if (hidFile == null || !hidFile.exists()) {
            System.err.println("Error finding /dev/hidg*!\n");
            System.exit(255);
            return;
        }


        //告知用户已找到hidg*文件
        System.out.printf("Start USBServerSocket at /dev/%s!\n", hidFile.getName());
        // 启动FileObserver
        observer.startWatching();

        try {
            //打开HID节点文件
            hidFileIsValid = true;
            device = new FileOutputStream(hidFile, true);

            //生成binder
            IBinder binder = new ISendCode.Stub() {
                @Override
                public void sendFull(byte[] fullCode) throws RemoteException {
                    if (hidFileIsValid)
                        try {
                            device.write(fullCode);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                }

                @Override
                public void close() throws RemoteException {
                    try {
                        System.out.println("Stop USBServerSocket!\n");
                        observer.stopWatching();
                        device.close();
                        System.exit(0);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            };
            //把binder填到一个可以用Intent来传递的容器中
            BinderContainer binderContainer = new BinderContainer(binder);
            // 创建 Intent 对象，并将binder作为附加参数
            Intent intent = new Intent("intent.sendBinder");
            intent.putExtra("binder", binderContainer);

            // 获取 IActivityManager 类
            Object iActivityManagerObj = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class).invoke(null, "activity"));
            // 获取 broadcastIntent 方法
            Method broadcastIntentMethod = Class.forName("android.app.IActivityManager").getDeclaredMethod(
                    "broadcastIntent",
                    IApplicationThread.class,
                    Intent.class,
                    String.class,
                    IIntentReceiver.class,
                    int.class,
                    String.class,
                    Bundle.class,
                    String[].class,
                    int.class,
                    Bundle.class,
                    boolean.class,
                    boolean.class,
                    int.class
            );
            // 调用 broadcastIntent 方法发送广播
            broadcastIntentMethod.invoke(
                    iActivityManagerObj,
                    null,
                    intent,
                    null,
                    null,
                    -1,
                    null,
                    null,
                    null,
                    0,
                    null,
                    false,
                    true,
                    -1
            );

        } catch (Exception e) {
            e.printStackTrace();
        }


        //用来保持进程不退出，同时如果用户输入exit则程序退出
        Scanner scanner = new Scanner(System.in);
        String inline;
        while ((inline = scanner.nextLine()) != null) {
            if (inline.equals("exit"))
                break;
        }

        System.out.println("Stop USBServerSocket!\n");

        observer.stopWatching();
        try {
            device.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.exit(0);
    }


}
