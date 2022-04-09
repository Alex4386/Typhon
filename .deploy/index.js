const { default: axios } = require('axios');
const fs = require("fs");
const figlet = require("figlet");
const chalk = require("chalk");
const FormData = require("form-data");

console.log(figlet.textSync("Typhon Deploy", "Small Slant"));
console.log(chalk.bold("Typhon Deploy"), "-", chalk.italic("automated deployment script for deploying plugins to minehub"));
console.log("Copyright (c) Alex4386, Distributed under WTFPL");
console.log();

const secretsFile = ".secrets.json";
console.log(chalk.blueBright("i"), "Finding Secrets file...");
if (!fs.existsSync(secretsFile)) {
    console.error(chalk.redBright("x"), "Secrets file missing!");
    process.exit(1);
}

console.log(chalk.blueBright("i"), "Reading Secrets file...");
const secrets = JSON.parse(fs.readFileSync(secretsFile, { encoding: "utf-8" }));
if (!(secrets.accessToken || secrets.refreshToken)) {
    console.error(chalk.redBright("x"), "Missing access token and refresh token!");
}

const meiliNGOAuth2Endpoint = "https://meiling.stella-api.dev/v1/oauth2";
const minehubCloudCrongifyAPI = "https://cloud.minehub.kr/crongify";

(async () => {
    const jarFile = process.argv[2];
    const destinationFile = process.argv[3] || jarFile;


    console.log(chalk.blueBright("i"), "Validating Commandline Input...");
    if (jarFile === undefined) {
        console.error(chalk.redBright("x"), "Invalid JarFile Input.");

        process.exit(1);
    }

    console.log(chalk.blueBright("i"), "Checking plugin file to upload...");
    if (!fs.existsSync(jarFile)) {
        console.error(chalk.redBright("x"), "JarFile not found.");

        process.exit(1);
    }

    console.log(chalk.blueBright("i"), "Validating secrets against meiliNG...");
    try {
        await axios.get(meiliNGOAuth2Endpoint + "/tokeninfo?access_token=" + secrets.accessToken);

        console.log(chalk.greenBright("v"), "Valid AccessToken Detected!");
    } catch(e) {
        // oh boy access token is expired!
        console.warn(chalk.yellowBright("!"), "Invalid AccessToken Detected! Reissuing via Refresh Token...");

        const body = new URLSearchParams();
        body.append("client_id", "0780b3f9-5e63-4e3e-9d49-39e20a215dcb")
        body.append("grant_type", "refresh_token");
        body.append("refresh_token", secrets.refreshToken);

        if (!secrets.refreshToken) {
            console.error(chalk.redBright("x"), "Refresh Token is blank. Can NOT reissue access_token.");
            process.exit(1);
        }

        try {
            const result = await axios.post(meiliNGOAuth2Endpoint + "/token", body.toString());
            secrets.accessToken = result.data.access_token;
            secrets.refreshToken = result.data.refresh_token ?? secrets.refreshToken;
        } catch(e) {
            console.error(chalk.redBright("x"), "Failed to issue new valid access token with refresh token, It seems refresh token has been invalidated");
            console.error(e);
            process.exit(1);
        }

        console.log(chalk.blueBright("i"), "updating secrets to json file...");
        fs.writeFileSync(secretsFile, JSON.stringify(secrets, null, 2));
    }

    console.log(chalk.greenBright("v"), "Credentials all set!");
    console.log(chalk.blueBright("i"), "Connecting to Minehub...");

    try {
        console.log(chalk.blueBright("i"), "Querying owned servers...");
        const result = await axios.get(minehubCloudCrongifyAPI+"/servers", {
            headers: {
                "Authorization": "Bearer "+secrets.accessToken,
            },
        });

        console.log(chalk.blueBright("i"), "Finding specified server...");
        let serverIdentifier = secrets.server;

        // Minehub provides full Pterodactyl user-land APIs under Crongify Account Translation Layer
        // that translates Stella IT Accounts (meiliNG) to Pterodactyl Native Accounts.
        if (!serverIdentifier) {
            console.error(chalk.redBright("x"), "The Server Identifier is not specified in secrets file.");
            process.exit(1);
        }

        if (result.data.data.filter(n => n.uuid.startsWith(serverIdentifier)).length !== 1) {
            console.error(chalk.redBright("x"), "Unable to find matching server.");
            process.exit(1);
        }

        console.log(chalk.greenBright("v"), "Found the server!");

        const listFiles = await axios.get(minehubCloudCrongifyAPI+"/servers/"+serverIdentifier+"/files/list?directory=/", {
            headers: {
                "Authorization": "Bearer "+secrets.accessToken,
            },
        });

        const isTherePluginsDir = listFiles.data.data.data.filter(n => n.attributes.mimetype === "inode/directory" && n.attributes.name === "plugins").length > 0;
        
        if (!isTherePluginsDir) {
            console.warn(chalk.yellowBright("!"), "Missing plugins directory, Creating one...");
            await axios.post(minehubCloudCrongifyAPI+"/servers/"+serverIdentifier+"/files/create-folder", {
                root: "/",
                name: "plugins",
            }, {
                headers: {
                    "Authorization": "Bearer "+secrets.accessToken,
                },
            });

            console.log(chalk.greenBright("v"), "Created plugins directory!");
        }


        const uploadReq = await axios.get(minehubCloudCrongifyAPI+"/servers/"+serverIdentifier+"/files/upload", {
            headers: {
                "Authorization": "Bearer "+secrets.accessToken,
            },
        });

        const uploadURL = new URL(uploadReq.data.data.attributes.url);
        uploadURL.searchParams.append("directory", "/plugins/");

        const formData = new FormData()
        formData.append("files", fs.readFileSync(jarFile), {
            filename: destinationFile,
            contentType: "application/octet-stream"
        });

        await axios.post(uploadURL.toString(), formData, {
            headers: formData.getHeaders(),
        });

        console.log(chalk.greenBright("v"), "Upload Success!");

        /*
        console.log(chalk.blueBright("i"), "Restarting server...");
        await axios.post(minehubCloudCrongifyAPI+"/servers/"+serverIdentifier+"/power", {
            signal: 'restart',
        }, {
            headers: {
                "Authorization": "Bearer "+secrets.accessToken,
            },
        });
        */

        console.log(chalk.blueBright("i"), "Reloading server...");
        await axios.post(minehubCloudCrongifyAPI+"/servers/"+serverIdentifier+"/command", {
            command: 'reload confirm',
        }, {
            headers: {
                "Authorization": "Bearer "+secrets.accessToken,
            },
        });



        console.log(chalk.greenBright("v"), "Restart Success!");
        process.exit(0);
    } catch(e) {
        console.error(chalk.redBright("x"), "Failed while processing request with Minehub Crongify API. Here are the details");
        console.error(e);
        process.exit(1);
    }  
})();
