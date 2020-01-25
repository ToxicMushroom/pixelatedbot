package me.melijn.melijnbot.objects.utils

import kotlinx.coroutines.future.await
import me.melijn.melijnbot.Container
import me.melijn.melijnbot.commands.moderation.*
import me.melijn.melijnbot.database.autopunishment.Punishment
import me.melijn.melijnbot.database.ban.Ban
import me.melijn.melijnbot.database.ban.SoftBan
import me.melijn.melijnbot.database.kick.Kick
import me.melijn.melijnbot.database.mute.Mute
import me.melijn.melijnbot.database.warn.Warn
import me.melijn.melijnbot.enums.LogChannelType
import me.melijn.melijnbot.enums.PunishmentType
import me.melijn.melijnbot.enums.RoleType
import me.melijn.melijnbot.objects.translation.getLanguage
import me.melijn.melijnbot.objects.translation.i18n
import me.melijn.melijnbot.objects.utils.checks.getAndVerifyLogChannelByType
import me.melijn.melijnbot.objects.utils.checks.getAndVerifyRoleByType
import net.dv8tion.jda.api.entities.Member

object PPUtils {

    suspend fun updatePP(member: Member, ppMap: Map<String, Long>, container: Container) {
        val guildId = member.guild.idLong
        val apWrapper = container.daoManager.autoPunishmentWrapper
        val oldPPMap = apWrapper.autoPunishmentCache.get(Pair(guildId, member.idLong)).await()
        apWrapper.set(guildId, member.idLong, ppMap)

        val apgWrapper = container.daoManager.autoPunishmentGroupWrapper
        val pgs = apgWrapper.autoPunishmentCache.get(guildId).await()

        val punishments = container.daoManager.punishmentWrapper.punishmentCache.get(guildId).await()
        for (pg in pgs) {
            val key = ppMap.keys.firstOrNull { key -> key == pg.groupName } ?: continue
            val newPoints = ppMap[key] ?: continue
            val oldPoints = oldPPMap.getOrDefault(pg.groupName, 0)
            val entries = pg.pointGoalMap.filter { (tp, _) -> tp > oldPoints && newPoints >= tp }


            for (entry in entries) {
                val punishment = punishments.first { punish -> punish.name == entry.value }
                applyPunishment(member, punishment, container)
                punishment.punishmentType
            }

            pg.pointGoalMap
        }
    }

    private suspend fun applyPunishment(member: Member, punishment: Punishment, container: Container) {
        when (punishment.punishmentType) {
            //TODO ("Permission checks and logging in case of missing stuff")
            PunishmentType.BAN -> {
                val delDays = punishment.extraMap.getInt("delDays", 0)
                val duration = punishment.extraMap.getLong("duration", -1)
                val dull = if (duration == -1L) null else duration

                applyBan(member, punishment, container, delDays, dull)
            }
            PunishmentType.SOFTBAN -> {
                val delDays = punishment.extraMap.getInt("delDays", 7)

                applySoftBan(member, punishment, container, delDays)
            }
            PunishmentType.MUTE -> {
                val duration = punishment.extraMap.getLong("duration", -1)
                val dull = if (duration == -1L) null else duration

                applyMute(member, punishment, container, dull)
            }
            PunishmentType.KICK -> {
                applyKick(member, punishment, container)
            }
            PunishmentType.WARN -> {
                applyWarn(member, punishment, container)
            }
        }
    }

    private suspend fun applyBan(member: Member, punishment: Punishment, container: Container, delDays: Int, duration: Long?) {
        val pc = member.user.openPrivateChannel().awaitOrNull()
        val jda = member.jda
        val guild = member.guild
        val lang = getLanguage(container.daoManager, member.idLong, guild.idLong)
        val banning = i18n.getTranslation(lang, "message.banning")
        val banningMessage = pc?.sendMessage(banning)?.awaitOrNull()

        val ban = Ban(
            guild.idLong,
            member.idLong,
            jda.selfUser.idLong,
            punishment.reason,
            null,
            null,
            System.currentTimeMillis(),
            duration?.times(1000)?.plus(System.currentTimeMillis()),
            true
        )

        container.daoManager.banWrapper.setBan(ban)
        val ex = member.ban(delDays, punishment.reason).awaitEX()
        if (ex != null) {
            val failed = i18n.getTranslation(lang, "message.banning.failed")
            banningMessage?.editMessage(failed)?.queue()
            return
        }

        val banMessageDM = getBanMessage(lang, guild, member.user, jda.selfUser, ban)
        val banMessageLog = getBanMessage(lang, guild, member.user, jda.selfUser, ban, true, member.user.isBot, banningMessage != null)
        banningMessage?.editMessage(banMessageDM)?.override(true)?.queue()

        val lcType = if (duration == null) LogChannelType.PERMANENT_BAN else LogChannelType.TEMP_BAN
        val channel = guild.getAndVerifyLogChannelByType(container.daoManager, lcType) ?: return
        sendEmbed(container.daoManager.embedDisabledWrapper, channel, banMessageLog)
    }

    private suspend fun applySoftBan(member: Member, punishment: Punishment, container: Container, delDays: Int) {
        val pc = member.user.openPrivateChannel().awaitOrNull()
        val jda = member.jda
        val guild = member.guild
        val lang = getLanguage(container.daoManager, member.idLong, guild.idLong)
        val softBanning = i18n.getTranslation(lang, "message.softbanning")
        val softBanningMessage = pc?.sendMessage(softBanning)?.awaitOrNull()

        val softBan = SoftBan(
            guild.idLong,
            member.idLong,
            jda.selfUser.idLong,
            punishment.reason,
            System.currentTimeMillis()
        )

        container.daoManager.softBanWrapper.addSoftBan(softBan)
        val ex = member.ban(delDays, punishment.reason).awaitEX()
        if (ex != null) {
            val failed = i18n.getTranslation(lang, "message.softbanning.failed")
            softBanningMessage?.editMessage(failed)?.queue()
            return
        }
        guild.unban(member.user).reason("softban").queue()

        val softBanMessageDM = getSoftBanMessage(lang, guild, member.user, jda.selfUser, softBan)
        val softBanMessageLog = getSoftBanMessage(lang, guild, member.user, jda.selfUser, softBan, true, member.user.isBot, softBanningMessage != null)
        softBanningMessage?.editMessage(softBanMessageDM)?.override(true)?.queue()

        val channel = guild.getAndVerifyLogChannelByType(container.daoManager, LogChannelType.SOFT_BAN) ?: return
        sendEmbed(container.daoManager.embedDisabledWrapper, channel, softBanMessageLog)
    }

    private suspend fun applyMute(member: Member, punishment: Punishment, container: Container, duration: Long?) {
        val jda = member.jda
        val guild = member.guild
        val daoManager = container.daoManager
        val lang = getLanguage(daoManager, member.idLong, guild.idLong)

        val mute = Mute(
            guild.idLong,
            member.idLong,
            jda.selfUser.idLong,
            punishment.reason,
            null,
            null,
            System.currentTimeMillis(),
            duration?.times(1000)?.plus(System.currentTimeMillis()),
            true
        )

        daoManager.muteWrapper.setMute(mute)
        val muteRole = guild.getAndVerifyRoleByType(daoManager, RoleType.MUTE, true) ?: return
        guild.addRoleToMember(member, muteRole).await()

        val muteMessageDM = getMuteMessage(lang, guild, member.user, jda.selfUser, mute)
        val pc = member.user.openPrivateChannel().awaitOrNull()
        val mutedMessage = pc?.sendMessage(muteMessageDM)?.awaitOrNull()

        val muteMessageLog = getMuteMessage(lang, guild, member.user, jda.selfUser, mute, true, member.user.isBot, mutedMessage != null)

        val lcType = if (duration == null) LogChannelType.PERMANENT_MUTE else LogChannelType.TEMP_MUTE
        val channel = guild.getAndVerifyLogChannelByType(daoManager, lcType) ?: return
        sendEmbed(daoManager.embedDisabledWrapper, channel, muteMessageLog)
    }

    private suspend fun applyKick(member: Member, punishment: Punishment, container: Container) {
        val pc = member.user.openPrivateChannel().awaitOrNull()
        val jda = member.jda
        val guild = member.guild
        val lang = getLanguage(container.daoManager, member.idLong, guild.idLong)
        val kicking = i18n.getTranslation(lang, "message.kicking")
        val kickingMessage = pc?.sendMessage(kicking)?.awaitOrNull()

        val kick = Kick(
            guild.idLong,
            member.idLong,
            jda.selfUser.idLong,
            punishment.reason,
            System.currentTimeMillis()
        )

        container.daoManager.kickWrapper.addKick(kick)
        val ex = member.kick(punishment.reason).awaitEX()
        if (ex != null) {
            val failed = i18n.getTranslation(lang, "message.kicking.failed")
            kickingMessage?.editMessage(failed)?.queue()
            return
        }

        val kickMessageDM = getKickMessage(lang, guild, member.user, jda.selfUser, kick)
        val kickMessageLog = getKickMessage(lang, guild, member.user, jda.selfUser, kick, true, member.user.isBot, kickingMessage != null)
        kickingMessage?.editMessage(kickMessageDM)?.override(true)?.queue()

        val channel = guild.getAndVerifyLogChannelByType(container.daoManager, LogChannelType.KICK) ?: return
        sendEmbed(container.daoManager.embedDisabledWrapper, channel, kickMessageLog)
    }

    private suspend fun applyWarn(member: Member, punishment: Punishment, container: Container) {
        val jda = member.jda
        val guild = member.guild
        val lang = getLanguage(container.daoManager, member.idLong, guild.idLong)

        val warn = Warn(
            guild.idLong,
            member.idLong,
            jda.selfUser.idLong,
            punishment.reason,
            System.currentTimeMillis()
        )

        container.daoManager.warnWrapper.addWarn(warn)

        val warnMessageDM = getWarnMessage(lang, guild, member.user, jda.selfUser, warn)
        val pc = member.user.openPrivateChannel().awaitOrNull()
        val kickedMessage = pc?.sendMessage(warnMessageDM)?.awaitOrNull()

        val warnMessageLog = getWarnMessage(lang, guild, member.user, jda.selfUser, warn, true, member.user.isBot, kickedMessage != null)

        val channel = guild.getAndVerifyLogChannelByType(container.daoManager, LogChannelType.KICK) ?: return
        sendEmbed(container.daoManager.embedDisabledWrapper, channel, warnMessageLog)
    }
}