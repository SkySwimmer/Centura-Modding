package org.asf.centuria.feraltweaks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.AccountManager;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.accounts.SaveMode;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.dms.DMManager;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.feraltweaks.api.versioning.IModVersionHandler;
import org.asf.centuria.feraltweaks.chatpackets.MarkConvoReadPacket;
import org.asf.centuria.feraltweaks.gamepackets.DisconnectPacket;
import org.asf.centuria.feraltweaks.gamepackets.ErrorPopupPacket;
import org.asf.centuria.feraltweaks.gamepackets.FtModPacket;
import org.asf.centuria.feraltweaks.gamepackets.NotificationPacket;
import org.asf.centuria.feraltweaks.gamepackets.OkPopupPacket;
import org.asf.centuria.feraltweaks.gamepackets.PlayerDisplayNameUpdatePacket;
import org.asf.centuria.feraltweaks.gamepackets.YesNoPopupPacket;
import org.asf.centuria.feraltweaks.http.DataProcessor;
import org.asf.centuria.modules.ICenturiaModule;
import org.asf.centuria.modules.ModuleManager;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.eventbus.EventListener;
import org.asf.centuria.modules.eventbus.IEventReceiver;
import org.asf.centuria.modules.events.accounts.AccountDisconnectEvent;
import org.asf.centuria.modules.events.accounts.AccountPreloginEvent;
import org.asf.centuria.modules.events.accounts.MiscModerationEvent;
import org.asf.centuria.modules.events.accounts.AccountDisconnectEvent.DisconnectType;
import org.asf.centuria.modules.events.chat.ChatLoginEvent;
import org.asf.centuria.modules.events.chat.ChatMessageBroadcastEvent;
import org.asf.centuria.modules.events.chatcommands.ChatCommandEvent;
import org.asf.centuria.modules.events.chatcommands.ModuleCommandSyntaxListEvent;
import org.asf.centuria.modules.events.maintenance.MaintenanceStartEvent;
import org.asf.centuria.modules.events.servers.APIServerStartupEvent;
import org.asf.centuria.modules.events.servers.ChatServerStartupEvent;
import org.asf.centuria.modules.events.servers.GameServerStartupEvent;
import org.asf.centuria.networking.chatserver.ChatClient;
import org.asf.centuria.networking.gameserver.GameServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 
 * FeralTweaks Server Module
 * 
 * @author Sky Swimmer
 *
 */
public class FeralTweaksModule implements ICenturiaModule {

	/**
	 * FeralTweaks Protocol Version
	 */
	public static int FT_VERSION = 2;

	public String ftUnsupportedErrorMessage;
	public String ftOutdatedErrorMessage;
	public String modDataVersion;
	public boolean enableByDefault;
	public boolean requireManagedSaveData;
	public boolean preventNonFTClients;
	public String ftDataPath;
	public String ftCachePath;
	public String upstreamServerJsonURL;

	public HashMap<String, Boolean> replicatingObjects = new HashMap<String, Boolean>();

	private HashMap<String, String> playerNames = new HashMap<String, String>();

	private long maintenanceStartTime;
	private boolean maintenanceTimerStarted;
	private boolean cancelMaintenance;

	@Override
	public String id() {
		return "feraltweaks";
	}

	@Override
	public String version() {
		return "beta-1.0.0-b3";
	}

	@Override
	public void init() {
		// Check config
		File configFile = new File("feraltweaks.conf");
		if (!configFile.exists()) {
			// Write config
			try {
				Files.writeString(configFile.toPath(), "enable-by-default=false\n" + "prevent-non-ft-clients=true\n"
						+ "data-path=feraltweaks/content\ncache-path=feraltweaks/cache\nupstream-server-json=https://emuferal.ddns.net:6970/data/server.json\n"
						+ "error-unauthorized=\nFeralTweaks is presently not enabled on your account!\\n\\nPlease uninstall the client modding project, contact the server administrator if you believe this is an error.\n"
						+ "error-outdated=Incompatible client!\\nYour client is currently out of date, restart the game to update the client mods.\n"
						+ "mod-data-version=1\n");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		// Read config
		HashMap<String, String> properties = new HashMap<String, String>();
		try {
			for (String line : Files.readAllLines(configFile.toPath())) {
				if (line.startsWith("#") || line.isBlank())
					continue;
				String key = line;
				String value = "";
				if (key.contains("=")) {
					value = key.substring(key.indexOf("=") + 1);
					key = key.substring(0, key.indexOf("="));
					if (key.startsWith("set-replication-for:")) {
						replicatingObjects.put(key.substring("set-replication-for:".length()),
								value.equalsIgnoreCase("enabled"));
					}
				}
				properties.put(key, value);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		enableByDefault = properties.getOrDefault("enable-by-default", "false").equalsIgnoreCase("true");
		preventNonFTClients = properties.getOrDefault("prevent-non-ft-clients", "false").equalsIgnoreCase("true");
		ftDataPath = properties.getOrDefault("data-path", "feraltweaks/content");
		ftCachePath = properties.getOrDefault("cache-path", "feraltweaks/cache");
		upstreamServerJsonURL = properties.getOrDefault("upstream-server-json",
				"https://emuferal.ddns.net:6970/data/server.json");
		ftOutdatedErrorMessage = properties.getOrDefault("error-outdated",
				"\nIncompatible client!\nYour client is currently out of date, restart the game to update the client mods.")
				.replaceAll("\\\\n", "\n");
		ftUnsupportedErrorMessage = properties.getOrDefault("error-unauthorized",
				"FeralTweaks is presently not enabled on your account!\\n\\nPlease uninstall the client modding project, contact the server administrator if you believe this is an error.")
				.replaceAll("\\\\n", "\n");
		modDataVersion = properties.getOrDefault("mod-data-version", "1");
		requireManagedSaveData = properties.getOrDefault("require-managed-saves", "false").equalsIgnoreCase("true");

		// Create data folders
		if (!new File(ftDataPath + "/feraltweaks/chartpatches").exists())
			new File(ftDataPath + "/feraltweaks/chartpatches").mkdirs();
		if (!new File(ftDataPath + "/clientmods/assemblies").exists())
			new File(ftDataPath + "/clientmods/assemblies").mkdirs();
		if (!new File(ftDataPath + "/clientmods/assets").exists())
			new File(ftDataPath + "/clientmods/assets").mkdirs();

		// Bind late events
		EventBus.getInstance().addEventReceiver(new LateEventContainer());

		// Start username refresher
		Thread th = new Thread(() -> {
			while (true) {
				// Go through users
				ArrayList<String> users = new ArrayList<String>();
				if (Centuria.gameServer != null)
					for (Player plr : Centuria.gameServer.getPlayers()) {
						users.add(plr.account.getAccountID());
						synchronized (playerNames) {
							String nm = GameServer.getPlayerNameWithPrefix(plr.account);
							if (!playerNames.containsKey(plr.account.getAccountID())
									|| !playerNames.get(plr.account.getAccountID()).equals(nm)) {
								updateUser(plr);
								playerNames.put(plr.account.getAccountID(), nm);
							}
						}
					}
				synchronized (playerNames) {
					String[] names = playerNames.keySet().toArray(t -> new String[t]);
					for (String id : names) {
						if (!users.contains(id))
							playerNames.remove(id);
					}
				}
				try {
					Thread.sleep(30000);
				} catch (InterruptedException e) {
				}
			}
		}, "User update handler (FeralTweaks)");
		th.setDaemon(true);
		th.start();

		// Start maintenance handler
		th = new Thread(() -> {
			long timeLastMessage = 0;
			while (true) {
				// Check
				if (maintenanceTimerStarted) {
					// Check cancel
					if (cancelMaintenance) {
						maintenanceStartTime = -1;
						maintenanceTimerStarted = false;
						cancelMaintenance = false;
						timeLastMessage = 0;
						continue;
					}

					// Check time remaining
					long remaining = maintenanceStartTime - System.currentTimeMillis();
					if (remaining <= 0) {
						// Start maintenance

						// Enable maintenance mode
						Centuria.gameServer.maintenance = true;

						// Dispatch maintenance event
						EventBus.getInstance().dispatchEvent(new MaintenanceStartEvent());

						// Cancel if maintenance is disabled
						if (!Centuria.gameServer.maintenance) {
							// Reset schedule
							maintenanceStartTime = -1;
							maintenanceTimerStarted = false;
							cancelMaintenance = false;
							timeLastMessage = 0;
							continue;
						}

						// Disconnect everyone but the staff
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (!plr.account.getSaveSharedInventory().containsItem("permissions")
									|| !GameServer.hasPerm(plr.account.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")) {
								// Dispatch event
								EventBus.getInstance().dispatchEvent(
										new AccountDisconnectEvent(plr.account, null, DisconnectType.MAINTENANCE));

								plr.client.sendPacket("%xt%ua%-1%__FORCE_RELOGIN__%");
							}
						}

						// Wait a bit
						int i = 0;
						while (Stream.of(Centuria.gameServer.getPlayers())
								.filter(plr -> !plr.account.getSaveSharedInventory().containsItem("permissions")
										|| !GameServer.hasPerm(
												plr.account.getSaveSharedInventory().getItem("permissions")
														.getAsJsonObject().get("permissionLevel").getAsString(),
												"admin"))
								.findFirst().isPresent()) {
							i++;
							if (i == 30)
								break;

							try {
								Thread.sleep(1000);
							} catch (InterruptedException e) {
							}
						}
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (!plr.account.getSaveSharedInventory().containsItem("permissions")
									|| !GameServer.hasPerm(plr.account.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")) {
								// Disconnect from the game server
								plr.client.disconnect();

								// Disconnect it from the chat server
								for (ChatClient cl : Centuria.chatServer.getClients()) {
									if (cl.getPlayer().getAccountID().equals(plr.account.getAccountID())) {
										cl.disconnect();
									}
								}
							}
						}

						// Reset schedule
						maintenanceStartTime = -1;
						maintenanceTimerStarted = false;
						cancelMaintenance = false;
						timeLastMessage = 0;

						// Send message
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (plr != null) {
								if (plr.getObject(FeralTweaksClientObject.class) == null
										|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
									// Send as DM
									Centuria.systemMessage(plr,
											"Server maintenance has started!\n\nOnly admins can remain ingame.", true);
								} else {
									// Show popup
									OkPopupPacket pkt = new OkPopupPacket();
									pkt.title = "Server Maintenance";
									pkt.message = "Server maintenance has started!\n\nOnly admins can remain ingame.";
									plr.client.sendPacket(pkt);
								}
							}
						}
						continue;
					}

					// Countdown messages
					String message = null;
					remaining = (remaining / 1000 / 60);

					// Find message
					switch ((int) remaining) {

					case 15:
					case 30:
					case 60:
					case 120:
					case 180:
					case 240: {
						// Few hours or minutes
						if (remaining < 60)
							message = "Servers are scheduled to go down for maintenance soon!\n" //
									+ "\n"//
									+ "Servers will go down in " + remaining
									+ " minute(s) for maintenance, during this time the servers will be unavailable.\n" //
									+ "\n" //
									+ "We will be back soon!";
						else
							message = "Servers are scheduled to go down for maintenance soon!\n" //
									+ "\n"//
									+ "Servers will go down in " + (remaining / 60)
									+ " hour(s) for maintenance, during this time the servers will be unavailable.\n" //
									+ "\n" //
									+ "We will be back soon!";
						break;
					}

					case 10:
					case 5:
					case 3:
					case 1: {
						// Very little time
						message = "WARNING! Servers maintenance is gonna start very soon!\n" //
								+ "\n" //
								+ "Only " + remaining
								+ " minute(s) remaining before the servers go down for maintenance!";
						break;
					}

					default: {
						if (remaining > 240) {
							SimpleDateFormat fmt = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss a", Locale.US);
							fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

							// More than 4 hours remaining
							// Check amount of time since last message
							long timeSinceLast = System.currentTimeMillis() - timeLastMessage;
							if (timeSinceLast >= (12 * 60 * 60 * 1000)) {
								// Create message
								message = "There is upcoming server maintenance scheduled.\n" //
										+ "\n" //
										+ "Servers are scheduled to go down for maintenance at "
										+ fmt.format(new Date(maintenanceStartTime))
										+ " UTC, during this time the servers will be unavailable.\n" //
										+ "\n" //
										+ "We will be back soon!";
							}
						}

						if (message == null) {
							if (timeLastMessage == 0) {
								// Generate message
								if (remaining <= 10) {
									message = "WARNING! Servers maintenance is gonna start very soon!\n" //
											+ "\n" //
											+ "Only " + remaining
											+ " minute(s) remaining before the servers go down for maintenance!";
								} else if (remaining < 60)
									message = "Servers are scheduled to go down for maintenance soon!\n" //
											+ "\n"//
											+ "Servers will go down in " + remaining
											+ " minute(s) for maintenance, during this time the servers will be unavailable.\n" //
											+ "\n" //
											+ "We will be back soon!";
								else
									message = "Servers are scheduled to go down for maintenance soon!\n" //
											+ "\n"//
											+ "Servers will go down in " + (remaining / 60)
											+ " hour(s) for maintenance, during this time the servers will be unavailable.\n" //
											+ "\n" //
											+ "We will be back soon!";
							}
						}
					}

					}

					// Check
					if (message != null) {
						// Send message
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (plr != null) {
								if (plr.getObject(FeralTweaksClientObject.class) == null
										|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
									// Send as DM
									Centuria.systemMessage(plr, message, true);
								} else {
									// Show popup
									OkPopupPacket pkt = new OkPopupPacket();
									pkt.title = "Upcoming Server Maintenance";
									pkt.message = message;
									plr.client.sendPacket(pkt);
								}
							}
						}
					}
					timeLastMessage = System.currentTimeMillis();
				}
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
			}
		}, "Server maintenance scheduler");
		th.setDaemon(true);
		th.start();
	}

	private void updateUser(Player plr) {
		// Send username update packet to all players
		PlayerDisplayNameUpdatePacket pkt = new PlayerDisplayNameUpdatePacket();
		pkt.id = plr.account.getAccountID();
		pkt.name = GameServer.getPlayerNameWithPrefix(plr.account);
		for (Player plr2 : Centuria.gameServer.getPlayers()) {
			if (plr2 != null) {
				if (plr2.getObject(FeralTweaksClientObject.class) != null
						&& plr2.getObject(FeralTweaksClientObject.class).isEnabled()) {
					// Send
					plr2.client.sendPacket(pkt);
				}
			}
		}
	}

	private class LateEventContainer implements IEventReceiver {
		@EventListener
		public void handleChatPrelogin(ChatLoginEvent event) {
			// Handshake feraltweaks
			if (event.getLoginRequest().has("feraltweaks")
					&& event.getLoginRequest().get("feraltweaks").getAsString().equals("enabled")) {
				// Handle FeralTweaks hanshake
				if (event.getLoginRequest().get("feraltweaks_protocol").getAsInt() != FT_VERSION) {
					// Handshake failure
					event.cancel();
					return;
				}

				// Check if FT is enabled
				if (!enableByDefault && !event.getAccount().getSaveSharedInventory().containsItem("feraltweaks")
						&& !event.getAccount().getSaveSpecificInventory().containsItem("feraltweaks")) {
					// Handshake failure
					event.cancel();
					return;
				}

				// Handshake success
				event.getClient().addObject(new FeralTweaksClientObject(true,
						event.getLoginRequest().get("feraltweaks_version").getAsString()));

				// Prepare to send unreads
				JsonObject pkt = new JsonObject();
				pkt.addProperty("eventId", "feraltweaks.unreadconversations");
				JsonArray arr = new JsonArray();

				// Load unreads
				if (event.getAccount().getSaveSharedInventory().containsItem("unreadconversations")) {
					arr = event.getAccount().getSaveSharedInventory().getItem("unreadconversations").getAsJsonArray();

					// Remove nonexistent items and rooms the player is no longer in
					// Dms are joined by now, so would gcs as this event is bound later than any
					// module normally binds, meaning the other modules are fired before this one
					ArrayList<JsonElement> toRemove = new ArrayList<JsonElement>();
					for (JsonElement ele : arr) {
						if (!DMManager.getInstance().dmExists(ele.getAsString())
								|| !event.getClient().isInRoom(ele.getAsString()))
							toRemove.add(ele);
					}
					for (JsonElement id : toRemove)
						arr.remove(id);

					// Save if needed
					if (toRemove.size() != 0)
						event.getAccount().getSaveSharedInventory().setItem("unreadconversations", arr);
				} else
					event.getAccount().getSaveSharedInventory().setItem("unreadconversations", arr);

				// Send packet
				pkt.add("conversations", arr);
				event.getClient().sendPacket(pkt);
			} else {
				if ((enableByDefault || event.getAccount().getSaveSharedInventory().containsItem("feraltweaks")
						|| event.getAccount().getSaveSpecificInventory().containsItem("feraltweaks"))
						&& preventNonFTClients) {
					// Unsupported
					event.cancel();
					return;
				}
				event.getClient().addObject(new FeralTweaksClientObject(false, null));
			}
		}
	}

	@EventListener
	public void disconnect(AccountDisconnectEvent event) {
		if (event.getAccount().getOnlinePlayerInstance() != null
				&& event.getAccount().getOnlinePlayerInstance().getObject(FeralTweaksClientObject.class) != null
				&& event.getAccount().getOnlinePlayerInstance().getObject(FeralTweaksClientObject.class).isEnabled()) {
			DisconnectPacket pkt = new DisconnectPacket();
			pkt.button = "Quit";
			pkt.title = "Disconnected";
			switch (event.getType()) {
			case BANNED:
				pkt.title = "Banned";
				pkt.message = "Your account was suspended and has been disconnected.";
				if (event.getReason() != null)
					pkt.message += "\n\nReason: " + event.getReason();
				break;
			case KICKED:
				pkt.message = "You were disconnected from the server.";
				if (event.getReason() != null)
					pkt.message += "\n\nReason: " + event.getReason();
				break;
			case MAINTENANCE:
				pkt.title = "Server Closed";
				pkt.message = "The server has been placed under maintenance, hope to be back soon!";
				break;
			case SERVER_SHUTDOWN:
				pkt.title = "Server Closed";
				pkt.message = "The server has been temporarily shut down, hope to be back soon!";
				break;
			case UNKNOWN:
				pkt.message = "Disconnected from server due to an unknown error.";
				break;
			}
			event.getAccount().getOnlinePlayerInstance().client.sendPacket(pkt);
			try {
				// Give time to disconnect
				Thread.sleep(500);
			} catch (InterruptedException e) {
			}
		}
	}

	@EventListener
	public void gameServerStartup(GameServerStartupEvent event) {
		event.registerPacket(new YesNoPopupPacket());
		event.registerPacket(new FtModPacket());
	}

	@EventListener
	public void chatMessageSent(ChatMessageBroadcastEvent event) {
		// Check room
		if (event.getClient().isInRoom(event.getConversationId())
				&& event.getClient().isRoomPrivate(event.getConversationId())) {
			// Find dm participants
			if (DMManager.getInstance().dmExists(event.getConversationId())) {
				String[] participants = DMManager.getInstance().getDMParticipants(event.getConversationId());
				for (String accId : participants) {
					// Find account
					CenturiaAccount acc = AccountManager.getInstance().getAccount(accId);
					if (acc == null)
						continue; // Wtf-

					// Check if online
					if (acc.getOnlinePlayerInstance() == null) {
						// Offline, check feraltweaks support
						if (enableByDefault || acc.getSaveSharedInventory().containsItem("feraltweaks")
								|| acc.getSaveSpecificInventory().containsItem("feraltweaks")) {
							// Add to unread history
							JsonArray arr = new JsonArray();

							// Load unreads
							if (acc.getSaveSharedInventory().containsItem("unreadconversations"))
								arr = acc.getSaveSharedInventory().getItem("unreadconversations").getAsJsonArray();

							// Check if present
							boolean found = false;
							for (JsonElement ele : arr) {
								if (ele.getAsString().equals(event.getConversationId())) {
									found = true;
									break;
								}
							}
							if (!found) {
								// Add unread
								arr.add(event.getConversationId());

								// Save
								acc.getSaveSharedInventory().setItem("unreadconversations", arr);
							}
						}
					}
				}
			}
		}
	}

	@EventListener
	public void apiStartup(APIServerStartupEvent event) {
		// Register custom processors
		event.getServer().registerProcessor(new DataProcessor());
	}

	@EventListener
	public void chatStartup(ChatServerStartupEvent event) {
		// Register custom chat packets
		event.registerPacket(new MarkConvoReadPacket());
	}

	@EventListener
	public void handleGamePrelogin(AccountPreloginEvent event) {
		// Handshake feraltweaks

		// Add mods to result json
		JsonObject modsJ = new JsonObject();
		modsJ.addProperty("feraltweaks", version());
		for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
			if (module instanceof IModVersionHandler) {
				modsJ.addProperty(module.id(), module.version());
			}
		}
		event.getLoginResponseParameters().addProperty("serverSoftwareName", "Centuria");
		event.getLoginResponseParameters().addProperty("serverSoftwareVersion", Centuria.SERVER_UPDATE_VERSION);
		event.getLoginResponseParameters().add("serverMods", modsJ);

		// Handshake
		try {
			// Parse nick variable
			boolean feralTweaks = false;
			XtReader rd = new XtReader(event.getAuthPacket().nick);
			while (rd.hasNext()) {
				String entry = rd.read();
				if (entry.equals("feraltweaks")) {
					// Verify the chain
					if (!rd.hasNext())
						break;
					String status = rd.read();
					if (!status.equals("enabled"))
						continue;
					if (!rd.hasNext())
						break; // Invalid
					int protVer = rd.readInt();
					if (!rd.hasNext())
						break; // Invalid
					String ver = rd.read();
					if (!rd.hasNext())
						break; // Invalid
					String dataVer = rd.read();
					if (!rd.hasNext())
						break; // Invalid
					int modCount = rd.readInt();
					HashMap<String, String> mods = new HashMap<String, String>();
					HashMap<String, HashMap<String, String>> handshakeRules = new HashMap<String, HashMap<String, String>>();
					for (int i = 0; i < modCount; i++) {
						// Read id
						if (!rd.hasNext())
							break; // Invalid
						String id = rd.read();

						// Read version
						if (!rd.hasNext())
							break; // Invalid
						String version = rd.read();

						// Read handshake rules
						if (!rd.hasNext())
							break; // Invalid
						int l = rd.readInt();
						HashMap<String, String> rules = new HashMap<String, String>();
						for (int i2 = 0; i2 < l; i2++) {
							// Read id
							if (!rd.hasNext())
								break; // Invalid
							String rID = rd.read();

							// Read version check string
							if (!rd.hasNext())
								break; /// Invalid
							String rVer = rd.read();
							rules.put(rID, rVer);
						}

						// Add
						mods.put(id, version);
						handshakeRules.put(id, rules);
					}
					if (!rd.hasNext() || !rd.read().equals("end"))
						break; // Invalid
					if (rd.hasNext())
						break; // Invalid

					// Check handshake
					if (protVer != FT_VERSION || (!dataVer.equals("undefined")
							&& !dataVer.equals(modDataVersion + "/" + Centuria.SERVER_UPDATE_VERSION))) {
						// Handshake failure
						event.getLoginResponseParameters().addProperty("errorMessage", ftOutdatedErrorMessage);
						event.setStatus(-26);
						return;
					}

					// Check if FT is enabled
					if (!enableByDefault && !event.getAccount().getSaveSharedInventory().containsItem("feraltweaks")
							&& !event.getAccount().getSaveSpecificInventory().containsItem("feraltweaks")) {
						// Handshake failure
						event.setStatus(-26);
						event.getLoginResponseParameters().addProperty("errorMessage", ftUnsupportedErrorMessage);
						return;
					}

					// Check managed saves if needed
					if (requireManagedSaveData && event.getAccount().getSaveMode() != SaveMode.MANAGED) {
						// Handshake failure
						event.setStatus(-26);
						event.getLoginResponseParameters().addProperty("errorMessage",
								"Please migrate to managed save data before continuing, you can do this from the account panel.");
						return;
					}

					// Log
					String modsStr = "";
					for (String id : mods.keySet()) {
						if (!modsStr.isEmpty())
							modsStr += ", ";
						modsStr += id + " (" + mods.get(id) + ")";
					}
					Centuria.logger.info("Player " + event.getAccount().getDisplayName() + " is logging in with "
							+ mods.size() + " client mod" + (mods.size() == 1 ? "" : "s") + " [" + modsStr + "]");

					// Verify handshake rules of server
					HashMap<String, String> localMods = new HashMap<String, String>();
					ArrayList<String> missingMods = new ArrayList<String>();
					ArrayList<String> incompatibleMods = new ArrayList<String>();
					localMods.put("feraltweaks", version());
					for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
						if (module instanceof IModVersionHandler) {
							localMods.put(module.id(), module.version());
							IModVersionHandler handler = (IModVersionHandler) module;
							Map<String, String> rules = handler.getClientModVersionRules();

							// Verify rules
							for (String id : rules.keySet()) {
								if (!mods.containsKey(id)) {
									// Missing
									if (!missingMods.contains(id))
										missingMods.add(id);
								} else {
									// Check version
									if (!verifyVersionRequirement(mods.get(id), rules.get(id))) {
										// Incompatible
										if (!incompatibleMods.contains(id))
											incompatibleMods.add(id);
									}
								}
							}
						}
					}

					// Verify
					if (missingMods.size() != 0 || incompatibleMods.size() != 0) {
						// Handshake error

						event.setStatus(-26);
						String msg = "Your current game installation is not compatible with the server.\n\n";

						// Build message
						String logMsg = "";
						msg += "Missing/outdated client mods:\n";
						boolean first = true;
						for (String mod : missingMods) {
							if (!first)
								msg += ", ";
							msg += mod;
							if (!logMsg.isEmpty())
								logMsg += ", ";
							logMsg += mod;
							first = false;
						}
						for (String mod : incompatibleMods) {
							if (!first)
								msg += ", ";
							if (!logMsg.isEmpty())
								logMsg += ", ";
							logMsg += mod;
							msg += mod;
							first = false;
						}

						// Log
						Centuria.logger.error("Player " + event.getAccount().getDisplayName()
								+ " failed to log in due to " + (missingMods.size() + incompatibleMods.size())
								+ " incompatible/missing CLIENT mod"
								+ ((missingMods.size() + incompatibleMods.size()) == 1 ? "" : "s") + " [" + logMsg
								+ "]");
						event.getLoginResponseParameters().addProperty("incompatibleClientMods", logMsg);
						event.getLoginResponseParameters().addProperty("incompatibleClientModCount",
								(missingMods.size() + incompatibleMods.size()));

						// Set result
						event.getLoginResponseParameters().addProperty("errorMessage", msg);
						return;
					}

					// Verify client mod rules
					incompatibleMods.clear();
					for (HashMap<String, String> rules : handshakeRules.values()) {
						// Verify rules
						for (String id : rules.keySet()) {
							if (!localMods.containsKey(id)) {
								// Missing
								if (!incompatibleMods.contains(id))
									incompatibleMods.add(id);
							} else {
								// Check version
								if (!verifyVersionRequirement(localMods.get(id), rules.get(id))) {
									// Incompatible
									if (!incompatibleMods.contains(id))
										incompatibleMods.add(id);
								}
							}
						}
					}

					// Verify
					if (incompatibleMods.size() != 0) {
						// Handshake error

						// Set status
						event.setStatus(-26);

						// Build message
						String logMsg = "";
						String msg = "You are running mods that require mods that require a up-to-date server mod:\n";
						boolean first = true;
						for (String mod : incompatibleMods) {
							if (!first)
								msg += ", ";
							if (!logMsg.isEmpty())
								logMsg += ", ";
							logMsg += mod;
							msg += mod;
							first = false;
						}

						// Log
						Centuria.logger
								.error("Player " + event.getAccount().getDisplayName() + " failed to log in due to "
										+ incompatibleMods.size() + " incompatible/missing SERVER mod"
										+ (incompatibleMods.size() == 1 ? "" : "s") + " [" + logMsg + "]");
						event.getLoginResponseParameters().addProperty("incompatibleServerMods", logMsg);
						event.getLoginResponseParameters().addProperty("incompatibleServerModCount",
								incompatibleMods.size());

						// Set result
						event.getLoginResponseParameters().addProperty("errorMessage", msg);
						return;
					}

					// Handshake success
					event.getClient().addObject(new FeralTweaksClientObject(true, ver));
					feralTweaks = true;

					// Send username update packet to all players
					PlayerDisplayNameUpdatePacket pkt = new PlayerDisplayNameUpdatePacket();
					pkt.id = event.getAccount().getAccountID();
					pkt.name = GameServer.getPlayerNameWithPrefix(event.getAccount());
					for (Player plr : Centuria.gameServer.getPlayers()) {
						if (plr != null) {
							if (plr.getObject(FeralTweaksClientObject.class) != null
									&& plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
								// Send
								plr.client.sendPacket(pkt);
							}
						}
					}

					// Set
					synchronized (playerNames) {
						playerNames.put(pkt.id, pkt.name);
					}
					break;
				}
			}

			if (!feralTweaks) {
				// No feraltweaks
				if ((enableByDefault || event.getAccount().getSaveSharedInventory().containsItem("feraltweaks")
						|| event.getAccount().getSaveSpecificInventory().containsItem("feraltweaks"))
						&& preventNonFTClients) {
					// Requires feraltweaks and its not installed/active
					// Set to error
					event.setStatus(-24);
					return;
				}
				event.getClient().addObject(new FeralTweaksClientObject(false, null));
			}
		} catch (Exception e) {
			// Uhh what
			event.setStatus(-1);
		}
	}

	@EventListener
	public void registerCommands(ModuleCommandSyntaxListEvent event) {
		if (event.hasPermission("moderator")) {
			// Warnings, announcements, etc
			event.addCommandSyntaxMessage("announce \"<message>\" [\"<title>\"]");
			event.addCommandSyntaxMessage("warn \"<player>\" \"<message>\" [\"<title>\"]");
			event.addCommandSyntaxMessage(
					"request \"<player>\" \"<message>\" [\"<title>\"] [\"<yes-button>\"] [\"<no-button>\"]");
			event.addCommandSyntaxMessage("notify \"<player>\" \"<message>\"");

			// Maintenance
			if (event.hasPermission("admin")) {
				event.addCommandSyntaxMessage("startmaintenancetimer <timer length in minutes>");
				event.addCommandSyntaxMessage("schedulemaintenance \"<MM/dd/yyyy HH:mm>\" (expects date/time in UTC)");
				event.addCommandSyntaxMessage("cancelmaintenance");
			}
		}
	}

	@EventListener
	public void runCommand(ChatCommandEvent event) {
		if (event.hasPermission("moderator")) {
			switch (event.getCommandID().toLowerCase()) {

			case "startmaintenancetimer": {
				if (event.hasPermission("admin")) {
					// Maintenance with timer

					// Set handled
					event.setHandled();

					// Check arguments
					if (event.getCommandArguments().length == 0) {
						event.respond("Error: missing argument: timer length");
						return;
					} else if (!event.getCommandArguments()[0].matches("^[0-9]+$")) {
						event.respond("Error: invalid argument: timer length: not a valid number");
						return;
					}

					// Check
					if (!maintenanceTimerStarted || cancelMaintenance) {
						// Schedule
						scheduleMaintenance(event.getAccount(), System.currentTimeMillis()
								+ (Integer.parseInt(event.getCommandArguments()[0]) * 60 * 1000));
						event.respond("Maintenance scheduled");
					} else {
						// Error
						event.respond("Error: there is a maintenance scheduled already");
						return;
					}

					return;
				}
			}

			case "schedulemaintenance": {
				if (event.hasPermission("admin")) {
					// Maintenance with timer

					// Set handled
					event.setHandled();

					// Check arguments
					if (event.getCommandArguments().length == 0) {
						event.respond("Error: missing argument: maintenance start date and time (UTC)");
						return;
					}

					// Parse
					SimpleDateFormat fmt = new SimpleDateFormat("MM/dd/yyyy HH:mm");
					fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
					Date start;
					try {
						start = fmt.parse(event.getCommandArguments()[0]);
					} catch (Exception e) {
						event.respond(
								"Error: invalid argument: date/time: not a valid date/time value (expected a value formatted 'MM/dd/yyyy HH:mm')");
						return;
					}

					// Check
					if (!maintenanceTimerStarted || cancelMaintenance) {
						// Schedule
						scheduleMaintenance(event.getAccount(), start.getTime());
						event.respond("Maintenance scheduled");
					} else {
						// Error
						event.respond("Error: there is a maintenance scheduled already");
						return;
					}

					return;
				}
			}

			case "cancelmaintenance": {
				if (event.hasPermission("admin")) {
					// Canceling maintenance

					// Set handled
					event.setHandled();

					// Check
					if (maintenanceTimerStarted && !cancelMaintenance) {
						event.respond("Maintenance cancelled");
						cancelMaintenance = true;
					} else {
						event.respond("Error: no maintenance scheduled");
						return;
					}

					// Log
					HashMap<String, String> details = new HashMap<String, String>();
					EventBus.getInstance()
							.dispatchEvent(new MiscModerationEvent("cancelmaintenance", "Server maintenance cancelled",
									details, event.getClient().getPlayer().getAccountID(), null));

					return;
				}
			}

			case "announce": {
				// Handle announcement commands

				event.setHandled();
				if (event.getCommandArguments().length == 0) {
					event.respond("Error: missing argument: message");
					return;
				}

				// Send announcement
				for (Player plr : Centuria.gameServer.getPlayers()) {
					if (plr != null) {
						if (plr.getObject(FeralTweaksClientObject.class) == null
								|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
							// Send as DM
							Centuria.systemMessage(plr, "Server announcement!\n" + event.getCommandArguments()[0],
									true);
						} else {
							// Show popup
							OkPopupPacket pkt = new OkPopupPacket();
							pkt.title = "Server Announcement";
							pkt.message = event.getCommandArguments()[0];
							if (event.getCommandArguments().length >= 2)
								pkt.title = event.getCommandArguments()[1];
							plr.client.sendPacket(pkt);
						}
					}
				}
				event.respond(
						"Sent announcement, note that for some this might be sent as a DM as not every client supports FeralTweaks.");

				// Log
				HashMap<String, String> details = new HashMap<String, String>();
				details.put("Message", event.getCommandArguments()[0]);
				EventBus.getInstance().dispatchEvent(new MiscModerationEvent("announce", "Made a server announcement",
						details, event.getClient().getPlayer().getAccountID(), null));

				break;
			}

			case "request": {
				// Handle request commands

				event.setHandled();
				if (event.getCommandArguments().length == 0) {
					event.respond("Error: missing argument: player");
					return;
				}
				if (event.getCommandArguments().length == 1) {
					event.respond("Error: missing argument: message");
					return;
				}

				// Find player
				String uuid = AccountManager.getInstance().getUserByDisplayName(event.getCommandArguments()[0]);
				if (uuid == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
				if (acc == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				Player plr = acc.getOnlinePlayerInstance();
				if (plr == null) {
					// Player offline
					event.respond(
							"Player is offline, cannot send popups to them unless they are ingame, please use DMs instead.");
					return;
				}

				// Check support
				if (plr.getObject(FeralTweaksClientObject.class) == null
						|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
					// Send as DM
					event.respond(
							"Error: the given player has no client mods that support this command, cannot send a popup.");
					return;
				} else {
				}

				// Send popup
				YesNoPopupPacket pkt = new YesNoPopupPacket();
				pkt.title = event.getClient().getPlayer().getDisplayName() + " asks";
				pkt.message = event.getCommandArguments()[1];
				pkt.yesButton = "Yes";
				pkt.noButton = "No";
				pkt.id = "playersent/" + event.getAccount().getAccountID();
				if (event.getCommandArguments().length >= 3)
					pkt.title = event.getCommandArguments()[2];
				if (event.getCommandArguments().length >= 4)
					pkt.yesButton = event.getCommandArguments()[3];
				if (event.getCommandArguments().length >= 5)
					pkt.noButton = event.getCommandArguments()[4];
				plr.client.sendPacket(pkt);
				event.respond("Request sent.");

				break;
			}

			case "warn": {
				// Handle warning commands

				event.setHandled();
				if (event.getCommandArguments().length == 0) {
					event.respond("Error: missing argument: player");
					return;
				}
				if (event.getCommandArguments().length == 1) {
					event.respond("Error: missing argument: message");
					return;
				}

				// Find player
				String uuid = AccountManager.getInstance().getUserByDisplayName(event.getCommandArguments()[0]);
				if (uuid == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
				if (acc == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				Player plr = acc.getOnlinePlayerInstance();
				if (plr == null) {
					// Player offline
					event.respond(
							"Player is offline, cannot warn them unless they are ingame, please use DMs instead.");
					return;
				}

				// Warn
				if (plr.getObject(FeralTweaksClientObject.class) == null
						|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
					// Send as DM
					Centuria.systemMessage(plr, "You have been warned!\n" + event.getCommandArguments()[1], true);
					event.respond(
							"Sent DM as system as the user does not have feraltweaks active, cannot display popups without it.");
				} else {
					// Show popup
					ErrorPopupPacket pkt = new ErrorPopupPacket();
					pkt.title = "You have been warned!";
					pkt.message = event.getCommandArguments()[1];
					if (event.getCommandArguments().length >= 3)
						pkt.title = event.getCommandArguments()[2];
					plr.client.sendPacket(pkt);
					event.respond("Warning sent.");
				}

				// Log
				HashMap<String, String> details = new HashMap<String, String>();
				details.put("Message", event.getCommandArguments()[1]);
				EventBus.getInstance().dispatchEvent(new MiscModerationEvent("request", "Issued a warning", details,
						event.getClient().getPlayer().getAccountID(), plr.account));

				break;
			}

			case "notify": {
				// Handle warning commands

				event.setHandled();
				if (event.getCommandArguments().length == 0) {
					event.respond("Error: missing argument: player");
					return;
				}
				if (event.getCommandArguments().length == 1) {
					event.respond("Error: missing argument: message");
					return;
				}

				// Find player
				String uuid = AccountManager.getInstance().getUserByDisplayName(event.getCommandArguments()[0]);
				if (uuid == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
				if (acc == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				Player plr = acc.getOnlinePlayerInstance();
				if (plr == null) {
					// Player offline
					event.respond(
							"Player is offline, cannot notify them unless they are ingame, please use DMs instead.");
					return;
				}

				// Check support
				if (plr.getObject(FeralTweaksClientObject.class) == null
						|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
					// Send as DM
					event.respond(
							"Error: the given player has no client mods that support this command, cannot send a popup.");
					return;
				} else {
				}

				// Show popup
				NotificationPacket pkt = new NotificationPacket();
				pkt.message = event.getCommandArguments()[1];
				plr.client.sendPacket(pkt);
				event.respond("Notification sent.");

				break;
			}
			}
		}
	}

	private void scheduleMaintenance(CenturiaAccount account, long startTime) {
		maintenanceStartTime = startTime;
		cancelMaintenance = false;
		maintenanceTimerStarted = true;

		// Log
		SimpleDateFormat fmt = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss a", Locale.US);
		fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
		HashMap<String, String> details = new HashMap<String, String>();
		details.put("Maintenance start date and time", fmt.format(new Date(startTime)) + " UTC");
		EventBus.getInstance().dispatchEvent(new MiscModerationEvent("maintenancescheduled",
				"Server maintenance was scheduled", details, account.getAccountID(), null));

	}

	private boolean verifyVersionRequirement(String version, String versionCheck) {
		for (String filter : versionCheck.split("\\|\\|")) {
			filter = filter.trim();
			if (verifyVersionRequirementPart(version, filter))
				return true;
		}
		return false;
	}

	private boolean verifyVersionRequirementPart(String version, String versionCheck) {
		// Handle versions
		for (String filter : versionCheck.split("&")) {
			filter = filter.trim();

			// Verify filter string
			if (filter.startsWith("!=")) {
				// Not equal
				if (version.equals(filter.substring(2)))
					return false;
			} else if (filter.startsWith("==")) {
				// Equal to
				if (!version.equals(filter.substring(2)))
					return false;
			} else if (filter.startsWith(">=")) {
				int[] valuesVersionCurrent = parseVersionValues(version);
				int[] valuesVersionCheck = parseVersionValues(filter.substring(2));

				// Handle each
				for (int i = 0; i < valuesVersionCheck.length; i++) {
					int val = valuesVersionCheck[i];

					// Verify lengths
					if (i > valuesVersionCurrent.length)
						break;

					// Verify value
					if (valuesVersionCurrent[i] < val)
						return false;
				}
			} else if (filter.startsWith("<=")) {
				int[] valuesVersionCurrent = parseVersionValues(version);
				int[] valuesVersionCheck = parseVersionValues(filter.substring(2));

				// Handle each
				for (int i = 0; i < valuesVersionCheck.length; i++) {
					int val = valuesVersionCheck[i];

					// Verify lengths
					if (i > valuesVersionCurrent.length)
						break;

					// Verify value
					if (valuesVersionCurrent[i] > val)
						return false;
				}
			} else if (filter.startsWith(">")) {
				int[] valuesVersionCurrent = parseVersionValues(version);
				int[] valuesVersionCheck = parseVersionValues(filter.substring(1));

				// Handle each
				for (int i = 0; i < valuesVersionCheck.length; i++) {
					int val = valuesVersionCheck[i];

					// Verify lengths
					if (i > valuesVersionCurrent.length)
						break;

					// Verify value
					if (valuesVersionCurrent[i] <= val)
						return false;
				}
			} else if (filter.startsWith("<")) {
				int[] valuesVersionCurrent = parseVersionValues(version);
				int[] valuesVersionCheck = parseVersionValues(filter.substring(1));

				// Handle each
				for (int i = 0; i < valuesVersionCheck.length; i++) {
					int val = valuesVersionCheck[i];

					// Verify lengths
					if (i > valuesVersionCurrent.length)
						break;

					// Verify value
					if (valuesVersionCurrent[i] >= val)
						return false;
				}
			} else {
				// Equal to
				if (!version.equals(filter))
					return false;
			}
		}

		// Valid
		return true;
	}

	private int[] parseVersionValues(String version) {
		ArrayList<Integer> values = new ArrayList<Integer>();

		// Parse version string
		String buffer = "";
		for (char ch : version.toCharArray()) {
			if (ch == '-' || ch == '.') {
				// Handle segment
				if (!buffer.isEmpty()) {
					// Check if its a number
					if (buffer.matches("^[0-9]+$")) {
						// Add value
						try {
							values.add(Integer.parseInt(buffer));
						} catch (Exception e) {
							// ... okay... add first char value instead
							values.add((int) buffer.charAt(0));
						}
					} else {
						// Check if its a full word and doesnt contain numbers
						if (buffer.matches("^[^0-9]+$")) {
							// It is, add first char value
							values.add((int) buffer.charAt(0));
						} else {
							// Add each value
							for (char ch2 : buffer.toCharArray())
								values.add((int) ch2);
						}
					}
				}
				buffer = "";
			} else {
				// Add to segment buffer
				buffer += ch;
			}
		}
		if (!buffer.isEmpty()) {
			// Check if its a number
			if (buffer.matches("^[0-9]+$")) {
				// Add value
				try {
					values.add(Integer.parseInt(buffer));
				} catch (Exception e) {
					// ... okay... add first char value instead
					values.add((int) buffer.charAt(0));
				}
			} else {
				// Check if its a full word and doesnt contain numbers
				if (buffer.matches("^[^0-9]+$")) {
					// It is, add first char value
					values.add((int) buffer.charAt(0));
				} else {
					// Add each value
					for (char ch : buffer.toCharArray())
						values.add((int) ch);
				}
			}
		}

		int[] arr = new int[values.size()];
		for (int i = 0; i < arr.length; i++)
			arr[i] = values.get(i);
		return arr;
	}
}
