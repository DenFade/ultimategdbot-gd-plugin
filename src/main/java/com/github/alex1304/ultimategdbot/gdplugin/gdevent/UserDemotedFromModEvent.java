package com.github.alex1304.ultimategdbot.gdplugin.gdevent;

import com.github.alex1304.jdash.entity.GDUser;

public class UserDemotedFromModEvent extends UserEvent {
	public UserDemotedFromModEvent(GDUser user) {
		super(user);
	}
}
