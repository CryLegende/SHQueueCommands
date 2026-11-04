package org.Cry_Legende.sHQueueCommands;

/**
 * Commande en attente d'un joueur hors ligne.
 *
 * @param command  La commande à exécuter en console, avec ses variables %player% et %uuid%.
 * @param queuedAt Date de mise en file, en millisecondes depuis l'epoch ; sert à la purge des commandes trop anciennes.
 */
public record QueuedCommand(String command, long queuedAt) {
}
