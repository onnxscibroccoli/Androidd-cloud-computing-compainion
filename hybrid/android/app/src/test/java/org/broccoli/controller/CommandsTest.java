package org.broccoli.controller;
import org.junit.Test;
import static org.junit.Assert.*;
public class CommandsTest {
    @Test public void tapIsArgumentArray() throws Exception {
        assertArrayEquals(new String[]{"/system/bin/input","tap","1","2"},Commands.argv("tap","{\"x\":1,\"y\":2}"));
    }
    @Test public void rejectsShellInjection() {
        assertThrows(Exception.class,()->Commands.argv("launch","{\"component\":\"a/b;id\"}"));
        assertThrows(Exception.class,()->Commands.argv("tap","{\"x\":\"1;id\",\"y\":2}"));
        assertThrows(Exception.class,()->Commands.argv("status","{\"extra\":true}"));
        assertThrows(Exception.class,()->Commands.argv("shell","{}"));
        assertThrows(Exception.class,()->Commands.argv("tap","{\"x\":-1,\"y\":2}"));
    }
}
