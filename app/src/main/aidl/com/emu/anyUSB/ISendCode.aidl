package com.emu.anyUSB;


interface ISendCode  {

    void sendFull(in byte[] fullCode) ;
    void close();

}
