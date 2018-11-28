package me.melijn.jda.commands.fun;

import me.melijn.jda.Helpers;
import me.melijn.jda.blub.Category;
import me.melijn.jda.blub.Command;
import me.melijn.jda.blub.CommandEvent;
import me.melijn.jda.utils.MessageHelper;
import me.melijn.jda.utils.CrapUtils;
import net.dv8tion.jda.core.entities.User;

import static me.melijn.jda.Melijn.PREFIX;

public class CryCommand extends Command {

    private CrapUtils crapUtils;

    public CryCommand() {
        this.commandName = "cry";
        this.description = "Shows a crying person [anime]";
        this.usage = PREFIX + commandName + " [user]";
        this.category = Category.FUN;
        this.aliases = new String[] {"sad"};
        crapUtils = CrapUtils.getWebUtilsInstance();
    }

    @Override
    protected void execute(CommandEvent event) {
        if (event.getGuild() == null || Helpers.hasPerm(event.getMember(), this.commandName, 0)) {
            String[] args = event.getArgs().split("\\s+");
            if (args.length == 0 || args[0].isBlank()) {
                crapUtils.getImage("cry", image -> MessageHelper.sendFunText("**" + event.getAuthor().getName() + "** is crying", image.getUrl(), event));
            } else if (args.length == 1) {
                User target = Helpers.getUserByArgsN(event, args[0]);
                if (target == null) {
                    event.reply(event.getAuthor().getAsMention() + " is crying because of rain");
                } else {
                    crapUtils.getImage("cry", image ->
                            MessageHelper.sendFunText("**" + target.getName() + "** made **" + event.getAuthor().getName() + "** cry", image.getUrl(), event)
                    );
                }
            }
        } else {
            event.reply("You need the permission `" + commandName + "` to execute this command.");
        }
    }
}
