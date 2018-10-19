package me.melijn.jda.commands.util;

import com.sun.management.OperatingSystemMXBean;
import me.melijn.jda.Helpers;
import me.melijn.jda.blub.Category;
import me.melijn.jda.blub.Command;
import me.melijn.jda.blub.CommandEvent;
import net.dv8tion.jda.bot.sharding.ShardManager;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.entities.Guild;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static me.melijn.jda.Melijn.PREFIX;

public class StatsCommand extends Command {

    public StatsCommand() {
        this.commandName = "stats";
        this.description = "Shows server stats";
        this.usage = PREFIX + commandName;
        this.category = Category.UTILS;
    }

    /* CREDITS TO DUNCTE123 FOR MOST OF THESE STATS AND DESIGN */

    @Override
    protected void execute(CommandEvent event) {
        OperatingSystemMXBean bean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        long totalMem = bean.getTotalPhysicalMemorySize() >> 20;
        long usedMem = totalMem - (bean.getFreePhysicalMemorySize() >> 20);
        long totalJVMMem = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() >> 20;
        long usedJVMMem = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
        int voiceChannels = 0;
        for (Guild guild : event.getJDA().asBot().getShardManager().getGuilds()) {
            if (guild.getAudioManager().isConnected() || guild.getAudioManager().isAttemptingToConnect())
                voiceChannels++;
        }
        ShardManager shardManager = event.getJDA().asBot().getShardManager();
        event.reply(new EmbedBuilder()
                .setColor(Helpers.EmbedColor)
                .setThumbnail(event.getJDA().getSelfUser().getAvatarUrl())
                .addField("Bot stats", "" +
                        "\n**Shards** " + shardManager.getShardsTotal() +
                        "\n**Unique users** " + shardManager.getUserCache().size() +
                        "\n**Guilds** " + shardManager.getGuildCache().size() +
                        "\n**Connected VoiceChannels** " + voiceChannels +
                        "\n**Uptime** " + Helpers.getDurationBreakdown(ManagementFactory.getRuntimeMXBean().getUptime()) +
                        "\n\u200B", false)
                .addField("Server Stats", "" +
                        "\n**CPU Usage** " + new DecimalFormat("###.###%").format(bean.getProcessCpuLoad()) +
                        "\n**Cores** " + bean.getAvailableProcessors() +
                        "\n**RAM Usage** " + usedMem + "MB/" + totalMem + "MB" +
                        "\n**System Uptime** " + Helpers.getDurationBreakdown(getSystemUptime()) +
                        "\n\u200B", false)
                .addField("JVM Stats", "" +
                        "\n**RAM Usage** " + usedJVMMem + "MB/" + totalJVMMem + "MB" +
                        "\n**Threads** " + Thread.activeCount() + "/" + Thread.getAllStackTraces().size(), false)
                .build());
    }

    public static long getSystemUptime() {
        try {
            long uptime = -1;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                Process uptimeProc = Runtime.getRuntime().exec("net stats workstation");
                BufferedReader in = new BufferedReader(new InputStreamReader(uptimeProc.getInputStream()));
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("Statistieken vanaf")) {
                        SimpleDateFormat format = new SimpleDateFormat("'Statistieken vanaf' dd/MM/yyyy hh:mm:ss");
                        Date boottime = format.parse(line);
                        uptime = System.currentTimeMillis() - boottime.getTime();
                        break;
                    } else if (line.startsWith("Statistics since")) {
                        SimpleDateFormat format = new SimpleDateFormat("'Statistics since' MM/dd/yyyy hh:mm:ss a");
                        Date boottime = format.parse(line);
                        uptime = System.currentTimeMillis() - boottime.getTime();
                        break;
                    }
                }
            } else if (os.contains("mac") || os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                Process uptimeProc = Runtime.getRuntime().exec("uptime");
                BufferedReader in = new BufferedReader(new InputStreamReader(uptimeProc.getInputStream()));
                String line = in.readLine();
                if (line != null) {
                    Pattern parse = Pattern.compile("((\\d+) days,)? (\\d+):(\\d+)");
                    Matcher matcher = parse.matcher(line);
                    if (matcher.find()) {
                        String _days = matcher.group(2);
                        String _hours = matcher.group(3);
                        String _minutes = matcher.group(4);
                        int days = _days != null ? Integer.parseInt(_days) : 0;
                        int hours = _hours != null ? Integer.parseInt(_hours) : 0;
                        int minutes = _minutes != null ? Integer.parseInt(_minutes) : 0;
                        uptime = (minutes * 60000) + (hours * 60000 * 60) + (days * 6000 * 60 * 24);
                    }
                }
            }
            return uptime;
        } catch (Exception e) {
            return -1;
        }
    }
}