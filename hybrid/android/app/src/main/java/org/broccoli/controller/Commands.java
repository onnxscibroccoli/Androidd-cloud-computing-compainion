package org.broccoli.controller;

import org.json.JSONObject;

public final class Commands {
    private Commands() {}
    public static String[] argv(String operation, String arguments) throws Exception {
        JSONObject a = new JSONObject(arguments);
        switch (operation) {
            case "status":
                require(a.length() == 0);
                return new String[]{"/system/bin/getprop", "ro.product.model"};
            case "snapshot":
                require(a.length() == 0);
                return new String[]{"/system/bin/uiautomator", "dump", "/data/local/tmp/broccoli-controller.xml"};
            case "tap":
                require(a.length() == 2 && a.has("x") && a.has("y"));
                return new String[]{"/system/bin/input", "tap", Integer.toString(integer(a,"x",0,16384)), Integer.toString(integer(a,"y",0,16384))};
            case "key":
                require(a.length() == 1 && a.has("code"));
                int k=integer(a,"code",0,200);
                require(java.util.Arrays.asList(3,4,19,20,21,22,23,61,66,67,82,111).contains(k));
                return new String[]{"/system/bin/input", "keyevent", Integer.toString(k)};
            case "launch":
                require(a.length() == 1 && a.has("component"));
                String c=a.getString("component");
                require(c.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+"));
                return new String[]{"/system/bin/am", "start", "-n", c};
            default: throw new IllegalArgumentException("Unsupported operation");
        }
    }
    private static int integer(JSONObject a,String key,int min,int max) throws Exception {
        Object value=a.get(key);
        require(value instanceof Integer);
        int v=(Integer)value;
        require(v>=min && v<=max);
        return v;
    }
    private static void require(boolean condition) { if(!condition) throw new IllegalArgumentException("Invalid arguments"); }
}
