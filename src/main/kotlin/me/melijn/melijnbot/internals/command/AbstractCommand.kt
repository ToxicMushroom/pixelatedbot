package me.melijn.melijnbot.internals.command

import kotlinx.coroutines.delay
import me.melijn.melijnbot.Container
import me.melijn.melijnbot.enums.PermState
import me.melijn.melijnbot.internals.threading.TaskManager
import me.melijn.melijnbot.internals.utils.SPACE_PATTERN
import me.melijn.melijnbot.internals.utils.addIfNotPresent
import me.melijn.melijnbot.internals.utils.message.sendInGuild
import me.melijn.melijnbot.internals.utils.message.sendMissingPermissionMessage
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import kotlin.collections.ArrayList

const val PLACEHOLDER_PREFIX = "prefix"

abstract class AbstractCommand(val root: String) {

    var name: String = ""
    var id: Int = 0
    var description = "$root.description"
    var syntax = "$root.syntax"
    var help = "$root.help"
    var arguments = "$root.arguments"
    var examples = "$root.examples"
    var cooldown: Long = 0 // millis
    var commandCategory: CommandCategory = CommandCategory.DEVELOPER
    var aliases: Array<String> = arrayOf()
    var discordChannelPermissions: Array<Permission> = arrayOf()
    var discordPermissions: Array<Permission> = arrayOf()
    var runConditions: Array<RunCondition> = arrayOf()
    var children: Array<AbstractCommand> = arrayOf()
    var permissionRequired: Boolean = false
    //var args: Array<CommandArg> = arrayOf() cannot put extra information after global definitions with this

    init {
        description = "$root.description"
    }

    private val cmdlogger = LoggerFactory.getLogger("cmd")

    protected abstract suspend fun execute(context: ICommandContext)
    suspend fun run(context: ICommandContext) {
        context.commandOrder = ArrayList(context.commandOrder + this).toList()

        val indexedCommand = context.commandOrder.withIndex().sortedBy { it.index }.last()
        val cmd = indexedCommand.value

        if (context.calculatedRoot.isEmpty()) context.calculatedRoot += cmd.id
        else context.calculatedRoot += "." + cmd.name

        context.calculatedCommandPartsOffset += (context.partSpaceMap[context.calculatedRoot]
            ?: 0) + 1 // +1 is for the index of the commandpart

        // Check for child commands
        if (context.commandParts.size > context.calculatedCommandPartsOffset && children.isNotEmpty()) {
            val currentRoot = context.calculatedRoot
            val currentOffset = context.calculatedCommandPartsOffset

            // Searches if needed for aliases
            if (!context.searchedAliases) {
                val aliasCache = context.daoManager.aliasWrapper
                if (context.isFromGuild) {
                    context.aliasMap.putAll(aliasCache.getAliases(context.guildId))
                }
                for ((cmd2, ls) in aliasCache.getAliases(context.authorId)) {
                    val currentList = (context.aliasMap[cmd2] ?: emptyList()).toMutableList()
                    for (alias in ls) {
                        currentList.addIfNotPresent(alias, true)
                    }

                    context.aliasMap[cmd2] = currentList
                }
                context.searchedAliases = true
            }

            // Searched for correct child that matches a custom alias
            for (child in children) {
                for ((cmdPath, aliases) in context.aliasMap) {
                    val subRoot = currentRoot + "." + child.name
                    if (cmdPath == subRoot) {
                        for (alias in aliases) {
                            val aliasParts = alias.split(SPACE_PATTERN)
                            if (aliasParts.size <= (context.commandParts.size - currentOffset)) {
                                val matches = aliasParts.withIndex().all {
                                    context.commandParts[it.index + currentOffset] == it.value
                                }
                                if (!matches) continue

                                // Matched a subcommand v
                                context.partSpaceMap[subRoot] = aliasParts.size - 1
                                child.run(context)

                                return
                            }
                        }
                    }
                }
            }

            for (child in children) {
                if (child.isCommandFor(context.commandParts[currentOffset])) {
                    child.run(context)
                    return
                }
            }
        }

        val permission = context.commandOrder.joinToString(".", transform = { command -> command.name.toLowerCase() })
        if (hasPermission(context, permission)) {
            context.initArgs()
            if (context.isFromGuild) {
                // Update cooldowns
                val pair1 = Pair(context.channelId, context.authorId)
                val map1 = context.daoManager.commandChannelCoolDownWrapper.executions[pair1]?.toMutableMap()
                    ?: hashMapOf()
                map1[id.toString()] = System.currentTimeMillis()
                context.daoManager.commandChannelCoolDownWrapper.executions[pair1] = map1

                val pair2 = Pair(context.guildId, context.authorId)
                val map2 = context.daoManager.commandChannelCoolDownWrapper.executions[pair2]?.toMutableMap()
                    ?: hashMapOf()
                map2[id.toString()] = System.currentTimeMillis()
                context.daoManager.commandChannelCoolDownWrapper.executions[pair2] = map2
            }
            try {
                val cmdId = context.commandOrder.first().id.toString() + context.commandOrder.drop(1)
                    .joinToString(".") { it.name }
                if (CommandClient.checksFailed(
                        context.container,
                        context.commandOrder.last(),
                        cmdId,
                        context.message,
                        true,
                        context.commandParts
                    )
                ) return
                cmdlogger.info("${context.guildN?.name ?: ""}/${context.author.name}◠: ${context.message.contentRaw}")
                val start = System.currentTimeMillis()
                try {
                    execute(context)
                } catch (t: Throwable) {
                    cmdlogger.error(
                        "↱ ${context.guildN?.name ?: ""}/${context.author.name}◡: ${context.message.contentRaw}",
                        t
                    )
                    t.sendInGuild(context, shouldSend = true)
                }

                // new year check
                val tz = context.getTimeZoneId()
                val now = Instant.now().atZone(tz)
                if (now.dayOfYear == 1 && !context.daoManager.newYearWrapper.contains(now.year, context.authorId)) {
                    context.channel.sendMessage(
                        MessageBuilder().setContent(
                            "\uD83D\uDDD3 **Happy New Year ${now.year}** **" + if (context.isFromGuild) {
                                context.member.asMention
                            } else {
                                context.author.asMention
                            } + "** \uD83C\uDF8A"
                        )
                            .setAllowedMentions(EnumSet.allOf(Message.MentionType::class.java))
                            .build()
                    ).queue()

                    context.daoManager.newYearWrapper.add(now.year, context.authorId)
                }


                if (context.isFromGuild && context.daoManager.supporterWrapper.getGuilds().contains(context.guildId)) {
                    TaskManager.async {
                        val timeMap = context.daoManager.removeInvokeWrapper.getMap(context.guildId)
                        val seconds = timeMap[context.textChannel.idLong] ?: timeMap[context.guildId] ?: return@async

                        if (!context.selfMember.hasPermission(
                                context.textChannel,
                                Permission.MESSAGE_MANAGE
                            )
                        ) return@async

                        delay(seconds * 1000L)
                        val message = context.message
                        context.container.botDeletedMessageIds.add(message.idLong)

                        if (!context.selfMember.hasPermission(
                                context.textChannel,
                                Permission.MESSAGE_MANAGE
                            )
                        ) return@async
                        message.delete().queue(null, { context.container.botDeletedMessageIds.remove(message.idLong) })
                    }
                }
                val second = System.currentTimeMillis()
                cmdlogger.info("${context.guildN?.name ?: ""}/${context.author.name}◡${(second - start) / 1000.0}: ${context.message.contentRaw}")
            } catch (t: Throwable) {
                cmdlogger.error(
                    "↱ ${context.guildN?.name ?: ""}/${context.author.name}◡: ${context.message.contentRaw}",
                    t
                )
                t.sendInGuild(context)
            }
            context.daoManager.commandUsageWrapper.addUse(context.commandOrder[0].id)
        } else {
            sendMissingPermissionMessage(context, permission)
        }
    }

    fun isCommandFor(input: String): Boolean {
        if (name.equals(input, true)) {
            return true
        }
        for (alias in aliases) {
            if (alias.equals(input, true)) {
                return true
            }
        }
        return false
    }

}

suspend fun hasPermission(context: ICommandContext, permission: String, required: Boolean = false): Boolean {
    if (!context.isFromGuild) return true
    if (context.member.isOwner || context.member.hasPermission(Permission.ADMINISTRATOR)) return true
    val guildId = context.guildId
    val authorId = context.authorId
    val daoManager = context.daoManager
    // Gives me better ability to help
    if (context.botDevIds.contains(authorId)) return true


    val channelId = context.channelId
    val userMap = daoManager.userPermissionWrapper.getPermMap(guildId, authorId)
    val channelUserMap = daoManager.channelUserPermissionWrapper.getPermMap(channelId, authorId)

    val lPermission = permission.toLowerCase()

    // permission checking for user specific channel overrides (these override all)
    if (channelUserMap.containsKey(lPermission) && channelUserMap[lPermission] != PermState.DEFAULT) {
        return channelUserMap[lPermission] == PermState.ALLOW
    }

    // permission checking for user specific permissions (these override all role permissions)
    if (userMap.containsKey(lPermission) && userMap[lPermission] != PermState.DEFAULT) {
        return userMap[lPermission] == PermState.ALLOW
    }

    var roleResult = PermState.DEFAULT
    var channelRoleResult = PermState.DEFAULT


    // Permission checking for roles
    for (roleId in (context.member.roles.map { role -> role.idLong } + context.guild.publicRole.idLong)) {
        channelRoleResult = when (
            daoManager.channelRolePermissionWrapper.getPermMap(channelId, roleId)[lPermission]
        ) {
            PermState.ALLOW -> PermState.ALLOW
            PermState.DENY -> if (channelRoleResult == PermState.DEFAULT) {
                PermState.DENY
            } else {
                channelRoleResult
            }
            else -> channelRoleResult
        }
        if (channelRoleResult == PermState.ALLOW) break
        if (channelRoleResult != PermState.DEFAULT) continue
        if (roleResult != PermState.ALLOW) {
            roleResult = when (context.daoManager.rolePermissionWrapper.getPermMap(roleId)[lPermission]) {
                PermState.ALLOW -> PermState.ALLOW
                PermState.DENY -> if (roleResult == PermState.DEFAULT) PermState.DENY else roleResult
                else -> roleResult
            }
        }
    }

    if (channelRoleResult != PermState.DEFAULT) {
        roleResult = channelRoleResult
    }


    return if (
        context.commandOrder[0].commandCategory == CommandCategory.ADMINISTRATION ||
        context.commandOrder[0].commandCategory == CommandCategory.MODERATION ||
        context.commandOrder[0].permissionRequired ||
        context.commandOrder.last().permissionRequired ||
        required
    ) {
        roleResult == PermState.ALLOW
    } else {
        roleResult != PermState.DENY
    }
}

suspend fun hasPermission(
    container: Container,
    message: Message,
    permission: String,
    category: CommandCategory? = null,
    required: Boolean = false
): Boolean {
    val member = message.member ?: return true
    if (member.isOwner || member.hasPermission(Permission.ADMINISTRATOR)) return true
    val guild = member.guild
    val guildId = guild.idLong
    val authorId = member.idLong

    // Gives me better ability to help
    if (container.settings.botInfo.developerIds.contains(authorId)) return true

    val channelId = message.textChannel.idLong
    val userMap = container.daoManager.userPermissionWrapper.getPermMap(guildId, authorId)
    val channelUserMap = container.daoManager.channelUserPermissionWrapper.getPermMap(channelId, authorId)

    val lPermission = permission.toLowerCase()

    // permission checking for user specific channel overrides (these override all)
    if (channelUserMap.containsKey(lPermission) && channelUserMap[lPermission] != PermState.DEFAULT) {
        return channelUserMap[lPermission] == PermState.ALLOW
    }

    // permission checking for user specific permissions (these override all role permissions)
    if (userMap.containsKey(lPermission) && userMap[lPermission] != PermState.DEFAULT) {
        return userMap[lPermission] == PermState.ALLOW
    }

    var roleResult = PermState.DEFAULT
    var channelRoleResult = PermState.DEFAULT

    // Permission checking for roles
    for (roleId in (member.roles.map { role -> role.idLong } + guild.publicRole.idLong)) {
        channelRoleResult =
            when (container.daoManager.channelRolePermissionWrapper.getPermMap(channelId, roleId)[lPermission]) {
                PermState.ALLOW -> PermState.ALLOW
                PermState.DENY -> if (channelRoleResult == PermState.DEFAULT) PermState.DENY else channelRoleResult
                else -> channelRoleResult
            }
        if (channelRoleResult == PermState.ALLOW) break
        if (channelRoleResult != PermState.DEFAULT) continue
        if (roleResult != PermState.ALLOW) {
            roleResult = when (container.daoManager.rolePermissionWrapper.getPermMap(roleId)[lPermission]) {
                PermState.ALLOW -> PermState.ALLOW
                PermState.DENY -> if (roleResult == PermState.DEFAULT) PermState.DENY else roleResult
                else -> roleResult
            }
        }
    }

    if (channelRoleResult != PermState.DEFAULT) roleResult = channelRoleResult

    return if (
        category == CommandCategory.ADMINISTRATION ||
        category == CommandCategory.MODERATION ||
        required
    ) {
        roleResult == PermState.ALLOW
    } else {
        roleResult != PermState.DENY
    }
}