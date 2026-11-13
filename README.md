<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHQueueCommands

*Met en file d'attente les commandes pour les joueurs hors ligne.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Met en file d'attente les commandes pour les joueurs hors ligne.

Les commandes en file depuis plus de `purge-after-days` jours (90 par défaut) sont supprimées sans être exécutées. La purge tourne au démarrage, toutes les heures et à `/shqueuecommands reload`.

Utilisé sur : **Opale**.

## Mode réseau (plusieurs Opale)

Avec **SHNetwork** chargé et `reseau.actif: true` (par défaut), une seule file sert tous les serveurs. Sans SHNetwork, rien ne change (`queued_commands.yml`).

- **Joueur connecté sur ce serveur** : la commande s'exécute tout de suite, comme avant.
- **Joueur connecté sur un autre serveur** : la commande y est exécutée tout de suite (`runFor` de SHNetwork), `%player%` et `%uuid%` déjà remplacés.
- **Joueur hors ligne** : la commande part dans la file commune (MariaDB, table `shqc_queue`) s'il est déjà venu sur le réseau (en ligne, connu de ce serveur, ou présent dans la table des premières connexions de SHPlayerTrack, `reseau.table-premieres-connexions`). À sa connexion, sur n'importe quel serveur, ce serveur réclame ses commandes après `execution-delay-ticks` (UPDATE conditionnel : un seul serveur les obtient), les exécute puis les retire. Un joueur reparti avant l'exécution retrouve ses commandes à sa prochaine connexion.
- **Purge** et contrôle des réclamations restées sans suite (serveur arrêté pendant l'exécution, signalées une fois dans la console pour une vérification à la main, jamais relancées d'office) : sur un seul serveur (`leader` SHNetwork, tâche `shqueuecommands`).
- **Reprise** : au premier démarrage en mode réseau, `queued_commands.yml` est repris une seule fois pour tout le réseau (dates gardées), puis chaque serveur renomme le sien en `queued_commands.yml.avant-reseau`. Base injoignable : le plugin se désactive au lieu de retomber sur le fichier local.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/shqueuecommands queue <joueur> <commande>` | `/queuecommand`, `/qc`, `/shqc`, `/shqueuecommand` | Exécute la commande tout de suite si le joueur est en ligne, sinon la met en file. | `shqueuecommands.execute` |
| `/shqueuecommands reload` | idem | Recharge `config.yml` et `messages.yml`, puis purge la file. Un fichier illisible annule le rechargement : l'ancienne configuration reste active et le fichier fautif est indiqué. La section `reseau` n'est lue qu'au démarrage. | `shqueuecommands.admin.reload` |
| `/shqueuecommands list [joueur]` | idem | Commandes en attente : le total et les files les plus longues, ou celles d'un joueur. Pas disponible en mode réseau. | `shqueuecommands.admin.list` |
| `/shqueuecommands clear <joueur>` | idem | Retire les commandes en attente d'un joueur. Pas disponible en mode réseau. | `shqueuecommands.admin.clear` |

`/qc` est pris par ce plugin parce qu'il démarre avant l'ancien QueueCommands (`loadbefore`).

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shqueuecommands.execute` | Permet d'utiliser la commande pour mettre en file d'attente les commandes. | `op` |
| `shqueuecommands.admin` | Reload, list et clear (parent des trois suivantes, ne contient pas `shqueuecommands.execute`). | `op` |
| `shqueuecommands.admin.reload` | Recharger `config.yml` et `messages.yml`. | `op` |
| `shqueuecommands.admin.list` | Consulter les commandes en attente. | `op` |
| `shqueuecommands.admin.clear` | Retirer les commandes en attente d'un joueur. | `op` |

## Configuration

Fichiers par défaut, avec des valeurs d'exemple. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHQueueCommands/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`. Les tests de la file commune (chaque commande réclamée par un seul serveur, reprise unique de la file, purge) tournent sur une vraie base MariaDB jetable : `SHQC_TEST_URL`, `SHQC_TEST_USER`, `SHQC_TEST_PASSWORD` ; sans elles ils sont ignorés.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
