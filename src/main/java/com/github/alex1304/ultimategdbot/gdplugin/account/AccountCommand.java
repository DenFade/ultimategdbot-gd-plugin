package com.github.alex1304.ultimategdbot.gdplugin.account;

import java.util.Objects;
import java.util.Set;

import com.github.alex1304.ultimategdbot.api.Command;
import com.github.alex1304.ultimategdbot.api.Context;
import com.github.alex1304.ultimategdbot.api.Plugin;
import com.github.alex1304.ultimategdbot.api.utils.BotUtils;
import com.github.alex1304.ultimategdbot.gdplugin.GDPlugin;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLinkedUsers;

import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

public class AccountCommand implements Command {
	
	private final GDPlugin plugin;
	private final AccountLinkCommand linkSubcmd;
	private final AccountUnlinkCommand unlinkSubcmd;
	
	public AccountCommand(GDPlugin plugin) {
		this.plugin = Objects.requireNonNull(plugin);
		this.linkSubcmd = new AccountLinkCommand(plugin);
		this.unlinkSubcmd = new AccountUnlinkCommand(plugin);
	}

	@Override
	public Mono<Void> execute(Context ctx) {
		final var authorId = ctx.getEvent().getMessage().getAuthor().get().getId().asLong();
		final var alias = BotUtils.joinAliases(getAliases());
		final var linksubalias = BotUtils.joinAliases(linkSubcmd.getAliases());
		final var unlinksubalias = BotUtils.joinAliases(unlinkSubcmd.getAliases());
		return ctx.getBot().getDatabase().findByID(GDLinkedUsers.class, authorId)
				.filter(GDLinkedUsers::getIsLinkActivated)
				.flatMap(linkedUser -> plugin.getGdClient().getUserByAccountId(linkedUser.getGdAccountId()))
				.map(user -> Tuples.of(true, "You are currently linked to the Geometry Dash account **" + user.getName() + "**!"))
				.defaultIfEmpty(Tuples.of(false, "You are not yet linked to any Geometry Dash account!"))
				.flatMap(tuple -> ctx.reply("You can link your Discord account with your Geometry Dash account "
						+ "to get access to cool stuff in UltimateGDBot. You can for example use the `profile` "
						+ "command without arguments to display your own info, let others easily access your "
						+ "profile by mentionning you, or appear in server-wide Geometry Dash leaderboards.\n\n"
						+ tuple.getT2() + "\n"
						+ (tuple.getT1() ? "If you want to unlink your account, run `" + ctx.getPrefixUsed() + alias + " " + unlinksubalias + "`"
								: "To start linking your account, run `" + ctx.getPrefixUsed() + alias + " " + linksubalias + " <your_gd_username>`")))
				.then();
	}

	@Override
	public Set<String> getAliases() {
		return Set.of("account");
	}

	@Override
	public Set<Command> getSubcommands() {
		return Set.of(linkSubcmd, unlinkSubcmd);
	}

	@Override
	public String getDescription() {
		return "Allows you to manage your connection with your Geometry Dash account.";
	}

	@Override
	public String getLongDescription() {
		return "Linking your account allows UltimateGDBot to etablish a mapping between Geometry Dash users and Discord users, "
				+ "which can unlock a lot of possibilities. For example you can use some commands by tagging directly a "
				+ "Discord user instead of typing his GD username, build a server-wide Geometry Dash leaderboard "
				+ "(see leaderboard command), and more. Use the `link` subcommand to start linking your account, "
				+ "then you need to follow instructions given by the command to complete the linking process. "
				+ "When you have followed all instructions, type `done` in chat. To unlink your account, use the subcommand `unlink`. "
				+ "Note that you can link several Discord accounts to the same GD account, but you can't link several GD accounts to "
				+ "the same Discord account. This is designed so if you lose access to your Discord account, you can still use a new "
				+ "Discord account to link.";
	}

	@Override
	public String getSyntax() {
		return "";
	}

	@Override
	public Plugin getPlugin() {
		return plugin;
	}
}