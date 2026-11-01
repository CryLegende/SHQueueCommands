package org.Cry_Legende.sHQueueCommands;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Pont vers AzLink, sans dépendance de compilation.
 * <p>
 * {@code azlink money ...} ne trouve un joueur que si AzLink l'a déjà reçu du site lors d'un fetch
 * (toutes les minutes, cache vidé à chaque redémarrage). Lancée quelques secondes après la connexion,
 * la commande échoue avec « Unable to find player » et les tokens sont perdus sans autre trace.
 * Une commande {@code azlink fetch} juste avant ne suffit pas : elle lance la requête sans l'attendre.
 */
final class AzLinkSync {

    private final SHQueueCommands plugin;
    private boolean broken; // réflexion impossible (AzLink modifié) : on retombe sur l'exécution directe

    AzLinkSync(SHQueueCommands plugin) {
        this.plugin = plugin;
    }

    /**
     * Joueur visé par une commande {@code azlink money <add|remove|set> <joueur> <montant>}, sinon null.
     */
    static String moneyTarget(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length >= 4 && parts[0].equalsIgnoreCase("azlink") && parts[1].equalsIgnoreCase("money")) {
            return parts[3];
        }
        return null;
    }

    /**
     * Vrai si AzLink connaît déjà ce joueur, ou s'il n'y a rien à attendre (AzLink absent ou illisible).
     */
    boolean knows(String playerName) {
        Object core = core();
        if (core == null) {
            return true;
        }
        try {
            Object users = core.getClass().getMethod("getUserManager").invoke(core);
            Optional<?> user = (Optional<?>) users.getClass().getMethod("getUserByName", String.class).invoke(users, playerName);
            return user.isPresent();
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable(e);
            return true;
        }
    }

    /**
     * Demande au site la liste à jour des joueurs connectés ; le futur se termine quand AzLink a rempli son cache.
     * AzLink ignore un fetch lancé moins de 5 s après le précédent : le futur se termine alors tout de suite.
     */
    CompletableFuture<?> fetch() {
        Object core = core();
        if (core == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return (CompletableFuture<?>) core.getClass().getMethod("fetch").invoke(core);
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable(e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private Object core() {
        if (broken) {
            return null;
        }
        Plugin azLink = Bukkit.getPluginManager().getPlugin("AzLink");
        if (azLink == null || !azLink.isEnabled()) {
            return null;
        }
        try {
            return azLink.getClass().getMethod("getPlugin").invoke(azLink);
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable(e);
            return null;
        }
    }

    private void disable(Exception e) {
        broken = true;
        plugin.getLogger().warning("AzLink illisible (" + e + ") : les commandes azlink money partent sans attendre le site.");
    }
}
