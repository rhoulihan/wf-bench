package com.wf.benchmark;

import com.wf.benchmark.command.CleanCommand;
import com.wf.benchmark.command.LoadCommand;
import com.wf.benchmark.command.QueryCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(
    name = "wf-bench",
    mixinStandardHelpOptions = true,
    version = "wf-bench 1.0.0",
    description = "CLI tool for benchmarking MongoDB API for Oracle Database",
    subcommands = {
        LoadCommand.class,
        QueryCommand.class,
        CleanCommand.class
    }
)
public class WfBenchmarkCli implements Callable<Integer> {

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose logging")
    boolean verbose;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new WfBenchmarkCli())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public boolean isVerbose() {
        return verbose;
    }
}
