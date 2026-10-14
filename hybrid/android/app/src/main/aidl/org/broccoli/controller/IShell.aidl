package org.broccoli.controller;
interface IShell {
    String execute(String operation, String arguments) = 1;
    int uid() = 2;
    void destroy() = 16777114;
}
