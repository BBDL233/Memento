package com.bbdl.plugin;

/**
 * 自定义死亡消息的数据载体。
 */
public class CustomDeathMsg {
    /** 被玩家击杀时的自定义消息模板 */
    String byPlayer;
    /** 因其他原因死亡时的自定义消息模板 */
    String byCause;
}