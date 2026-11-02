package com.example.finance;

import java.io.PrintStream;
import java.util.Objects;

public final class ConsolePrinter {

    private final PrintStream out;

    public ConsolePrinter(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out не может быть null");
    }

    public static ConsolePrinter stdout() {
        return new ConsolePrinter(System.out);
    }

    public void println(String line) {
        out.println(line);
    }

    public void prompt(String text) {
        out.print(text);
        out.flush();
    }
}
