<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHQueueCommands

*Met en file d'attente les commandes pour les joueurs hors ligne.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Met en file d'attente les commandes pour les joueurs hors ligne.

Utilisé sur : **Opale**.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/queuecommand` | `/qc`, `/shqc`, `/shqueuecommand` | Met en file d'attente une commande pour un joueur ou l'exécute directement. | `shqueuecommands.execute` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shqueuecommands.execute` | Permet d'utiliser la commande pour mettre en file d'attente les commandes. | `op` |

## AzLink

Une commande `azlink money ...` ne passe que si AzLink a déjà reçu le joueur du site (mise à jour toutes les minutes, vidée à chaque redémarrage). Juste après une connexion, elle échouait avec « Unable to find player » et les tokens étaient perdus. Le plugin demande donc la mise à jour au site et attend sa réponse avant de lancer la commande (3 essais espacés de 6 s). Sans réponse, la commande reste dans la file pour la connexion suivante ; les autres commandes passent quand même.

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHQueueCommands/
└── <a href="src/main/resources/config.yml">config.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
