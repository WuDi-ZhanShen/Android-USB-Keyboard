package com.emu.anyUSB;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

public class MainActivity extends Activity {

    public static byte[] scanCode = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}; //8字节的USB HID键盘回报数据

    Button button;//负责显示当前USB键盘服务连接状态

    View.OnClickListener clickListener;//按钮的点击事件
    View.OnLongClickListener longClickListener;//按钮的长按事件

    ISendCode iSendCode = null;//接口，用于和HIDSocket通信

    //用于接收HIDSocket发出的广播，并从广播中解析出binder，然后将binder转换为接口
    final public BroadcastReceiver myReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //拿到广播中包含附加参数，解析出binder
            BinderContainer binderContainer = intent.getParcelableExtra("binder");
            IBinder binder = binderContainer.getBinder();
            //如果binder已经失去活性了，则不再继续解析
            if (!binder.pingBinder()) return;

            //将binder转换为接口
            iSendCode = ISendCode.Stub.asInterface(binder);
            Toast.makeText(context, "成功连接USB键盘服务", Toast.LENGTH_SHORT).show();
            //设置button的文字和点击切换小键盘功能
            SpannableString spannableString = new SpannableString("成功连接USB键盘服务\n点我切换小键盘");
            spannableString.setSpan(new ForegroundColorSpan(Color.GREEN), 0, spannableString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            button.setText(spannableString);
            button.setOnClickListener(clickListener);
            button.setOnLongClickListener(longClickListener);
            //设置HID键盘面板的所有按键们的触摸事件
            setButtonsOnClick();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //设置一下主界面的状态栏颜色之类的
        setContentView(R.layout.activity_main);
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            window.setNavigationBarContrastEnforced(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);


        //将HIDSocket.dex从assets解压至/sdcard/Android/data
        String file1 = getExternalFilesDir(null).getPath() + "/HIDSocket.dex";
        if (!new File(file1).exists())
            try {
                InputStream is = getAssets().open("HIDSocket.dex");
                FileOutputStream fileOutputStream = new FileOutputStream(file1);
                byte[] buffer = new byte[1024];
                int byteRead;
                while (-1 != (byteRead = is.read(buffer))) {
                    fileOutputStream.write(buffer, 0, byteRead);
                }
                is.close();
                fileOutputStream.flush();
                fileOutputStream.close();
            } catch (IOException ignored) {
            }


        //设置主界面状态按钮的点击事件
        button = findViewById(R.id.button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("USB键盘需要您设备的内核支持configfs文件系统，且设备已有root权限。\n\n1.请您先安装USB Gadget Tool软件并授予其root权限，然后点击主界面的第一个Gadget，再点击ADD FUNCTION ---- Keyboard。\n\n2.请将手机和电脑之间用USB数据线连接，并反复拔插1~2次，直到您发现手机的/dev目录中出现了叫做hidg0或者hidg1的节点文件。\n\n3.请在具有root权限的终端使用以下命令来启动USB键盘服务：\n\nexport CLASSPATH=" + file1 + ";app_process /system/bin com.emu.anyUSB.USBLocalSocket\n\n您应当看到类似于\"Start USBServerSocket at /dev/hidg0!\"这样的命令结果。\n\n4.打开本APP，您会看到主界面显示\"已连接USB键盘服务\"。并且您已经可以用手机当做键盘来控制电脑了。")
                        .setTitle("帮助")
                        .setNegativeButton("复制命令", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("c", "export CLASSPATH=" + file1 + ";app_process /system/bin com.emu.anyUSB.USBLocalSocket"));
                                Toast.makeText(MainActivity.this, "命令已复制到剪切板：\nexport CLASSPATH=" + file1 + ";app_process /system/bin com.emu.anyUSB.USBLocalSocket", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
            }
        });


        //查看设备是否支持configfs，也就是查看 getprop sys.usb.configfs 命令的结果
        try {
            Class<?> clz = Class.forName("android.os.SystemProperties");
            Method method = clz.getDeclaredMethod("get", String.class, String.class);
            String result = (String) method.invoke(null, "sys.usb.configfs", "0");
            if (result == null || !result.equals("1")) {
                new AlertDialog.Builder(this)
                        .setMessage("您设备的内核不支持configfs! 将在3秒后退出")
                        .setTitle("内核不支持")
                        .setCancelable(false)
                        .show();
                Toast.makeText(this, "您设备的内核不支持configfs! 将在3秒后退出", Toast.LENGTH_SHORT).show();
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                }, 3000);
            }
        } catch (Exception ignored) {
        }
        Toast.makeText(this, "您设备的内核支持configfs! ", Toast.LENGTH_SHORT).show();

        //注册广播接收器，用来接收HIDSocket.dex发来的广播。广播中含有一个binder，我们接收到binder之后就可以和HIDSocket.dex通信了。
        registerReceiver(myReceiver, new IntentFilter("intent.sendBinder"));


        //设置切换小键盘时的动画
        LinearLayout totalLayout = findViewById(R.id.totalLayout);
        LinearLayout letterLayout = findViewById(R.id.letterLayout);
        LinearLayout numericLayout = findViewById(R.id.numericLayout);
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(200L);
        ObjectAnimator animator = ObjectAnimator.ofFloat(null, "scaleY", 0.0f, 1.0f);
        transition.setAnimator(2, animator);
        totalLayout.setLayoutTransition(transition);


        //点击切换小键盘的listener，它会在接收到广播之后设置给button。
        clickListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (numericLayout.getVisibility() == View.VISIBLE) {
                    numericLayout.setVisibility(numericLayout.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    letterLayout.setVisibility(letterLayout.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                } else {
                    letterLayout.setVisibility(letterLayout.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    numericLayout.setVisibility(numericLayout.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
            }
        };
        longClickListener = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("关闭USB键盘服务并退出APP吗？")
                        .setMessage("\n如果您选择\"关闭并退出\"，则USB键盘服务也会被关闭。\n\n如果您选择\"仅退出APP\"，则不会关闭USB键盘服务，您下次打开APP后也就不需要再次启动服务。\n")
                        .setNegativeButton("关闭并退出", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                try {
                                    if (iSendCode != null)
                                        iSendCode.close();
                                } catch (RemoteException e) {
                                    e.printStackTrace();
                                }
                                finish();
                            }
                        })
                        .setPositiveButton("仅退出APP", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                finish();
                            }
                        })
                        .show();
                return true;
            }
        };

    }

    void setButtonsOnClick() {
        //遍历所有非控制按键，设定其点击事件
        for (View button : new View[]{findViewById(R.id.ESC), findViewById(R.id.F1), findViewById(R.id.F2), findViewById(R.id.F3), findViewById(R.id.F4), findViewById(R.id.F5), findViewById(R.id.F6), findViewById(R.id.F7), findViewById(R.id.F8), findViewById(R.id.F9), findViewById(R.id.F10), findViewById(R.id.F11), findViewById(R.id.F12), findViewById(R.id.s53), findViewById(R.id.s30), findViewById(R.id.s31), findViewById(R.id.s32), findViewById(R.id.s33), findViewById(R.id.s34), findViewById(R.id.s35), findViewById(R.id.s36), findViewById(R.id.s37), findViewById(R.id.s38), findViewById(R.id.s39), findViewById(R.id.s45), findViewById(R.id.s46), findViewById(R.id.s42), findViewById(R.id.TAB), findViewById(R.id.Q), findViewById(R.id.W), findViewById(R.id.E), findViewById(R.id.R), findViewById(R.id.T), findViewById(R.id.Y), findViewById(R.id.U), findViewById(R.id.I), findViewById(R.id.O), findViewById(R.id.P), findViewById(R.id.s47), findViewById(R.id.s48), findViewById(R.id.s49), findViewById(R.id.CAP), findViewById(R.id.A), findViewById(R.id.S), findViewById(R.id.D), findViewById(R.id.F), findViewById(R.id.G), findViewById(R.id.H), findViewById(R.id.J), findViewById(R.id.K), findViewById(R.id.L), findViewById(R.id.s51), findViewById(R.id.s52), findViewById(R.id.s40), findViewById(R.id.Z), findViewById(R.id.X), findViewById(R.id.C), findViewById(R.id.V), findViewById(R.id.B), findViewById(R.id.N), findViewById(R.id.M), findViewById(R.id.s54), findViewById(R.id.s55), findViewById(R.id.s56), findViewById(R.id.SPACE), findViewById(R.id.INSERT), findViewById(R.id.HOME), findViewById(R.id.PAGEUP), findViewById(R.id.NUMLOCK), findViewById(R.id.s84), findViewById(R.id.s85), findViewById(R.id.s86), findViewById(R.id.DELETE), findViewById(R.id.END), findViewById(R.id.PAGEDOWN), findViewById(R.id.s95), findViewById(R.id.s96), findViewById(R.id.s97), findViewById(R.id.s87), findViewById(R.id.s92), findViewById(R.id.s93), findViewById(R.id.s94), findViewById(R.id.UPARROW), findViewById(R.id.s89), findViewById(R.id.s90), findViewById(R.id.s91), findViewById(R.id.s88), findViewById(R.id.LEFTARROW), findViewById(R.id.DOWNARROW), findViewById(R.id.RIGHTARROW), findViewById(R.id.s98), findViewById(R.id.s99)}) {
            //aByte是这个按键的键码，我在Layout中为这些按键指定了键码在其tag中
            byte aByte = (byte) Integer.parseInt((String) button.getTag());
            button.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                        //按下时，文字变绿色，同时向scanCode中加入这个键码
                        ((Button) view).setTextColor(Color.GREEN);
                        addKey(aByte);
                        //将新的scanCode发给HIDSocket，由HIDSocket负责写入节点
                        try {
                            iSendCode.sendFull(scanCode);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    } else if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                        //松开时，文字变黑色，同时从scanCode中去掉这个键码
                        ((Button) view).setTextColor(Color.BLACK);
                        removeKey(aByte);
                        //将新的scanCode发给HIDSocket，由HIDSocket负责写入节点
                        try {
                            iSendCode.sendFull(scanCode);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                    return false;
                }
            });
        }

        //对所有控制键（一共就8个，左右Alt、左右Shift、左右Ctrl、左右Windows键）设定其点击事件
        for (View button : new View[]{findViewById(R.id.LSHIFT), findViewById(R.id.RSHIFT), findViewById(R.id.LCTRL), findViewById(R.id.LGUI), findViewById(R.id.LALT), findViewById(R.id.RALT), findViewById(R.id.RGUI), findViewById(R.id.RCTRL)}) {
            //aByte依然是按钮Layout中标出来的Tag
            byte aByte = (byte) Integer.parseInt((String) button.getTag());
            button.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {

                    if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                        //按下时，文字变红色，同时将scanCode的第一个字节的某一位置一
                        ((Button) view).setTextColor(Color.RED);
                        scanCode[0] = (byte) (scanCode[0] | (1 << aByte));
                        //将修改后的scanCode发送给HIDSocket
                        try {
                            iSendCode.sendFull(scanCode);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    } else if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                        //松开时，文字变黑色，同时将scanCode的第一个字节中的某一位置零
                        ((Button) view).setTextColor(Color.BLACK);
                        scanCode[0] = (byte) (scanCode[0] & ~(1 << aByte));
                        //将修改后的scanCode发送给HIDSocket
                        try {
                            iSendCode.sendFull(scanCode);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                    return false;
                }
            });
        }
    }

    private void addKey(byte keyCode) {
        for (int i = 2; i < 8; i++) {
            if (scanCode[i] == 0) {
                scanCode[i] = keyCode;
                break;
            }
        }
    }

    private void removeKey(byte keyCode) {
        for (int i = 2; i < 8; i++) {
            if (scanCode[i] == keyCode) {
                scanCode[i] = 0;
                break;
            }
        }
    }

    @Override
    protected void onDestroy() {
        //在退出时，取消注册广播接收器，同时关闭HIDSocket
        unregisterReceiver(myReceiver);
        super.onDestroy();
    }
}