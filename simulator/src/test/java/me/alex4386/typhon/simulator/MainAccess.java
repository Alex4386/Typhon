package me.alex4386.typhon.simulator;

import java.io.IOException;
import java.io.PrintStream;

/** Test access to {@link Main#run} without {@code System.exit}. */
public final class MainAccess {
    private MainAccess() {}

    public static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        return Main.run(args, out, err);
    }
}
