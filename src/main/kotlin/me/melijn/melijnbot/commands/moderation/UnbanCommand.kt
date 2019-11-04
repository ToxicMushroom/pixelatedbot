package me.melijn.melijnbot.commands.moderation

import kotlinx.coroutines.future.await
import me.melijn.melijnbot.database.ban.Ban
import me.melijn.melijnbot.enums.LogChannelType
import me.melijn.melijnbot.objects.command.AbstractCommand
import me.melijn.melijnbot.objects.command.CommandCategory
import me.melijn.melijnbot.objects.command.CommandContext
import me.melijn.melijnbot.objects.translation.PLACEHOLDER_USER
import me.melijn.melijnbot.objects.translation.i18n
import me.melijn.melijnbot.objects.utils.*
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import java.awt.Color

class UnbanCommand : AbstractCommand("command.unban") {

    init {
        id = 25
        name = "unban"
        aliases = arrayOf("pardon")
        commandCategory = CommandCategory.MODERATION
        discordPermissions = arrayOf(Permission.BAN_MEMBERS)
    }

    override suspend fun execute(context: CommandContext) {
        val guild = context.guild
        if (context.args.isEmpty()) {
            sendSyntax(context)
            return
        }
        val language = context.getLanguage()
        val targetUser = retrieveUserByArgsNMessage(context, 0) ?: return

        var unbanReason = context.rawArg
            .replaceFirst(context.args[0], "")
            .trim()
        if (unbanReason.isBlank()) unbanReason = "/"

        unbanReason = unbanReason.trim()

        val activeBan: Ban? = context.daoManager.banWrapper.getActiveBan(context.guildId, targetUser.idLong)
        val ban: Ban = activeBan
            ?: Ban(context.guildId,
                targetUser.idLong,
                null,
                "/"
            )
        ban.unbanAuthorId = context.authorId
        ban.unbanReason = unbanReason
        ban.endTime = System.currentTimeMillis()
        ban.active = false

        val banAuthor = ban.banAuthorId?.let { context.shardManager.getUserById(it) }

        try {
            guild.retrieveBan(targetUser).await()
            try {
                guild.unban(targetUser).await()
                context.daoManager.banWrapper.setBan(ban)

                //Normal success path
                val msgLc = getUnbanMessage(language, context.guild, targetUser, banAuthor, context.author, ban, true)


                val privateChannel = targetUser.openPrivateChannel().awaitNE()
                privateChannel?.let {
                    try {
                        val msg = sendEmbed(it, msgLc)
                        continueUnbanning(context, targetUser, ban, banAuthor, msg[0])
                    } catch (t: Throwable) {
                        continueUnbanning(context, targetUser, ban, banAuthor, null)
                    }
                } ?: continueUnbanning(context, targetUser, ban, banAuthor, null)


            } catch (t: Throwable) {
                //Sum ting wrong
                val msg = i18n.getTranslation(language, "$root.failure")
                    .replace(PLACEHOLDER_USER, targetUser.asTag)
                    .replace("%cause%", t.message ?: "/")
                sendMsg(context, msg)
            }
        } catch (t: Throwable) {
            //Not banned anymore

            val msg = i18n.getTranslation(language, "$root.notbanned")
                .replace(PLACEHOLDER_USER, targetUser.asTag)
            sendMsg(context, msg)

            if (activeBan != null) {
                context.daoManager.banWrapper.setBan(ban)
            }
        }
    }

    private suspend fun continueUnbanning(context: CommandContext, targetUser: User, ban: Ban, banAuthor: User?, unbanningMessage: Message? = null) {
        val guild = context.guild
        val unbanAuthor = context.author
        val language = context.getLanguage()
        val isBot = targetUser.isBot
        val received = unbanningMessage != null
        val lcMsg = getUnbanMessage(
            language, guild, targetUser, banAuthor, unbanAuthor, ban, true, isBot, received
        )


        val logChannelWrapper = context.daoManager.logChannelWrapper
        val logChannelId = logChannelWrapper.logChannelCache.get(Pair(context.guildId, LogChannelType.UNBAN)).await()
        val logChannel = guild.getTextChannelById(logChannelId)
        logChannel?.let { it1 -> sendEmbed(context.daoManager.embedDisabledWrapper, it1, lcMsg) }


        val success = i18n.getTranslation(language, "$root.success")
            .replace(PLACEHOLDER_USER, targetUser.asTag)
            .replace("%reason%", ban.unbanReason ?: "/")
        sendMsg(context, success)
    }
}

fun getUnbanMessage(
    language: String,
    guild: Guild,
    bannedUser: User,
    banAuthor: User?,
    unbanAuthor: User,
    ban: Ban,
    lc: Boolean = false,
    isBot: Boolean = false,
    received: Boolean = true
): MessageEmbed {
    val eb = EmbedBuilder()

    val banDuration = ban.endTime?.let { endTime ->
        getDurationString((endTime - ban.startTime))
    } ?: i18n.getTranslation(language, "infinite")

    var description = "```LDIF\n"
    if (!lc) {
        description += i18n.getTranslation(language, "message.punishment.description.nlc")
            .replace("%guildName%", guild.name)
            .replace("%guildId%", guild.id)
    }

    val deletedAccount = i18n.getTranslation(language, "message.deletedaccount")
    description += i18n.getTranslation(language, "message.punishment.unban.description")
        .replace("%banAuthor%", banAuthor?.asTag ?: deletedAccount)
        .replace("%banAuthorId%", ban.banAuthorId.toString())
        .replace("%unBanAuthorId%", ban.unbanAuthorId.toString())
        .replace("%unBanned%", bannedUser.asTag)
        .replace("%unBannedId%", ban.bannedId.toString())
        .replace("%banReason%", ban.reason)
        .replace("%unbanReason%", ban.unbanReason ?: "/")
        .replace("%duration%", banDuration)
        .replace("%startTime%", (ban.startTime.asEpochMillisToDateTime()))
        .replace("%endTime%", (ban.endTime?.asEpochMillisToDateTime() ?: "none"))

    val extraDesc: String = if (!received || isBot) {
        i18n.getTranslation(language,
            if (isBot) {
                "message.punishment.extra.bot"
            } else {
                "message.punishment.extra.dm"
            }
        )
    } else {
        ""
    }
    description += extraDesc
    description += "```"

    val author = i18n.getTranslation(language, "message.punishment.unban.author")
        .replace(PLACEHOLDER_USER, unbanAuthor.asTag)
        .replace("%spaces%", " ".repeat(45).substring(0, 45 - unbanAuthor.name.length) + "\u200B")

    eb.setAuthor(author, null, unbanAuthor.effectiveAvatarUrl)
    eb.setDescription(description)
    eb.setThumbnail(bannedUser.effectiveAvatarUrl)
    eb.setColor(Color.GREEN)
    return eb.build()
}