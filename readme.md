# Automated Voice Surveys

### ⏱ 15 min build time

## Why build automated voice surveys?

Surveys are a great way to gather feedback about a product or a service. In this MessageBird Developer Tutorial, we'll look at a company that wants to collect surveys over the phone by providing their customers a feedback number that they can call and submit their opinion as voice messages that the company's support team can listen to on a website and incorporate that feedback into the next version of the product. This team should be able to focus their attention on the input on their own time instead of having to wait and answer calls. Therefore, the feedback collection itself is fully automated.

## Getting Started

The sample application is built in Java 1.8 using the [Spark framework](http://sparkjava.com/) with [Handlebars templates](http://sparkjava.com/documentation#views-and-templates). You can download or clone the complete source code from the [MessageBird Developer Tutorials GitHub repository](https://github.com/messagebirdguides/automated-surveys-java) to run the application on your computer and follow along with the tutorial. To run the sample, you will need [Java 1.8](https://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) and [Maven](https://maven.apache.org/) installed.

The sample application uses [MongoDB](https://mongodb.github.io/mongo-java-driver/) to provide an in-memory database for testing, so you don't need to configure an external database. As the mock loses data when you restart the application you need to replace it with a real server when you want to develop this sample into a production application.

## Designing the Call Flow

Call flows in MessageBird are sequences of steps. Each step can be a different action, such as playing an audio file, speaking words through text-to-speech (TTS), recording the caller's voice or transferring the call to another party. The call flow for this survey application alternates two types of actions: saying the question (`say` action) and recording an answer (`record` action). Other action types are not required. The whole flow begins with a short introduction text and ends on a "Thank you" note, both of which are implemented as `say` actions.

The survey application generates the call flow dynamically through Java code and provides it on a webhook endpoint as a JSON response that MessageBird can parse. It does not, however, return the complete flow at once. The generated steps always end on a `record` action with the `onFinish` attribute set to the same webhook endpoint URL. This approach simplifies the collection of recordings because whenever the caller provides an answer, an identifier for the recording is sent with the next webhook request. The endpoint will then store information about the answer to the question and return additional steps: either the next question together with its answer recording step or, if the caller has reached the end of the survey, the final "Thank you" note.

The sample implementation contains only a single survey. For each participant, we create a (mocked) database entry that includes a unique MessageBird-generated identifier for the call, their number and an array of responses. As the webhook is requested multiple times for each caller, once in the beginning and once for each answer they record, the length of the responses array indicates their position within the survey and determines the next step.

All questions are stored as an array in the file `questions.json` to keep them separate from the implementation. 

## Prerequisites for Receiving Calls

### Overview

Participants take part in a survey by calling a dedicated virtual phone number. MessageBird accepts the call and contacts the application on a _webhook URL_, which you assign to your number on the MessageBird Dashboard using a flow. A [webhook](https://en.wikipedia.org/wiki/Webhook) is a URL on your site that doesn't render a page to users but is like an API endpoint that can be triggered by other servers. Every time someone calls that number, MessageBird checks that URL for instructions on how to interact with the caller.

### Exposing your Development Server with ngrok

When working with webhooks, an external service like MessageBird needs to access your application, so the URL must be public. During development, though, you're typically working in a local development environment that is not publicly available. There are various tools and services available that allow you to quickly expose your development environment to the Internet by providing a tunnel from a public URL to your local machine. One of the most popular tools is [ngrok](https://ngrok.com/).

You can [download ngrok here for free](https://ngrok.com/download) as a single-file binary for almost every operating system, or optionally sign up for an account to access additional features.

You can start a tunnel by providing a local port number on which your application runs. We will run our Java server on port 4567, so you can launch your tunnel with this command:

```
ngrok http 4567
```

After you've launched the tunnel, ngrok displays your temporary public URL along with some other information. We'll need that URL in a minute.

Another common tool for tunneling your local machine is [localtunnel.me](https://localtunnel.me/), which you can have a look at if you're facing problems with ngrok. It works in virtually the same way but requires you to install [NPM](https://www.npmjs.com/) first.

### Getting an Inbound Number

A requirement for programmatically taking voice calls is a dedicated inbound number. Virtual telephone numbers live in the cloud, i.e., a data center. MessageBird offers numbers from different countries for a low monthly fee. Here's how to purchase one:

1. Go to the [Numbers](https://dashboard.messagebird.com/en/numbers) section of your MessageBird account and click Buy a number.
2. Choose the country in which you and your customers are located and make sure the _Voice_ capability is selected.
3. Choose one number from the selection and the duration for which you want to pay now. Buy a number screenshot
  ![Buy a number screenshot](https://developers.messagebird.com/assets/images/screenshots/automatedsurveys-node/buy-a-number.png)
4. Confirm by clicking **Buy Number**.

Excellent, you have set up your first virtual number!

### Connecting the Number to your Application

So you have a number now, but MessageBird has no idea what to do with it. That's why you need to define a _Flow_ next that ties your number to your webhook:

1. Open the Flow Builder and click **Create new flow**.
2. In the following dialog, choose **Create Custom Flow**.
  ![Create custom flow screenshot](https://developers.messagebird.com/assets/images/screenshots/automatedsurveys-node/create-a-new-flow.png)
3. Give your flow a name, such as "Survey Participation", select _Phone Call_ as the trigger and continue with **Next**. Create Flow, Step 1
  ![Create Flow, Step 1](https://developers.messagebird.com/assets/images/screenshots/automatedsurveys-node/setup-new-flow.png)
4. Configure the trigger step by ticking the box next to your number and click **Save**.
  ![Create Flow, Step 2](https://developers.messagebird.com/assets/images/screenshots/automatedsurveys-php/create-flow-2.png)

5. Press the small **+** to add a new step to your flow and choose **Fetch call flow from URL**.
  ![Create Flow, Step 3](https://developers.messagebird.com/assets/images/screenshots/automatedsurveys-php/create-flow-3.png)

6. Paste the localtunnel base URL into the form and append `/callStep` to it - this is the name of our webhook handler route. Click **Save**.
  ![Create Flow, Step 4](https://developers.messagebird.com/assets/images/screenshots/automatedsurveys-php/create-flow-4.png)

7. Hit **Publish** and your flow becomes active!

## Implementing the Call Steps

The routes `get /callStep` and `post /callStep` in `AutomatedVoiceSurvey.json` contains the implementation of the survey call flow:

```java
get("/callStep", (req, res) -> handleCallStep(req, res, mongoClient, questions));
post("/callStep", (req, res) -> handleCallStep(req, res, mongoClient, questions));

```

`handleCallStep` starts with the basic structure for a hash object called `flow`, which we'll extend depending on where we are within our survey:

```java
// Prepare a Call Flow that can be extended
JSONObject flow = new JSONObject();
JSONArray steps = new JSONArray();
flow.put("title", "Survey Call Step");
```

Next, we connect to MongoDB, select a collection and try to find an existing call:

``` java
MongoDatabase database = mongoClient.getDatabase("myproject");
MongoCollection<Document> collection = database.getCollection("survey_participants");

String callID = req.queryParams("callID");
Document doc = collection.find(eq("callId", callID)).first();
```

The application continues inside the callback function. First, we determine the ID (i.e., array index) of the next question, which is 0 for new participants or the number of existing answers plus one for existing ones:

``` java
// Determine the next question
Integer questionID = 0;

if (doc != null) {
    List<Document> responses = (List<Document>) doc.get("responses");
    questionID = responses.size() + 1;
}
```

For new participants, we also need to create a document in the MongoDB collection and persist it to the database. This record contains the identifier of the call and the caller ID, which are taken from the query parameters sent by MessageBird as part of the webhook (i.e., call flow fetch) request, `callID` and `destination` respectively. It includes an empty responses array as well.

```java
else {
    // Create new participant database entry
    Document entry = new Document("callId", req.queryParams("callID"))
            .append("number", req.queryParams("destination"))
            .append("responses", new JSONArray());
    collection.insertOne(entry);
}
```

The answers are persisted by adding them to the responses array and then updating the document in the MongoDB collection. For every answer we store two identifiers from the parsed JSON request body: the `legId` that identifies the caller in a multi-party voice call and is required to fetch the recording, as well as the id of the recording itself which we store as `recordingId`:

```java
if (questionID > 0) {
    JSONParser parser = new JSONParser();
    JSONObject requestPayload;
    try {
        requestPayload = (JSONObject) parser.parse(req.body());
        // Unless we're at the first question, store the response
        // of the previous question
        Document response = new Document().append("legId", requestPayload.get("legId"))
                .append("recordingId", requestPayload.get("id"));

        collection.updateOne(eq("callId", req.queryParams("callID")), Updates.addToSet("responses", response));
    } catch (ParseException e) {
        e.printStackTrace();
    }
}
```

Now it's time to ask a question. Let's first check if we reached the end of the survey. That is determined by whether the question index equals the length of the questions list and therefore is out of bounds of the array, which means there are no further questions. If so, we thank the caller for their participation:

```java
if (questionID == questions.size()) {
    // All questions have been answered
    steps.add(say("You have completed our survey. Thank you for participating!"));
}
```

You'll notice the `say()` function. It is a small helper function we've declared separately in the initial section of `AutomatedVoiceSurvey.java` to simplify the creation of `say` steps as we need them multiple times in the application. The function returns the action in the format expected by MessageBird so it can be added to the steps of a flow using `push()`, as seen above.

A function like this allows setting options for `say` actions at a central location. You can modify it if you want to, for example, specify another language or voice:

```java
public static JSONObject say(String payload) {
    JSONObject result = new JSONObject();
    JSONObject options = new JSONObject();
    options.put("payload", payload);
    options.put("voice", "male");
    options.put("language", "en-US");
    result.put("action", "say");
    result.put("options", options);

    return result;
}
```

Back in the route, there's an `else`-block that handles all questions other than the last. There's another nested `if`-statement in it, though, to treat the first question, as we need to read a welcome message to our participant before the question:

```java
if (questionID == 0) {
    // Before first question, say welcome
    steps.add(say(String.format("Welcome to our survey! You will be asked %s questions. The answers will be recorded. Speak your response for each and press any key on your phone to move on to the next question. Here is the first question:", questions.size())));
}
```

Finally, here comes the general logic used for each question:

* Ask the question using `say`.
* Request a recording.

```java
// Ask next question
steps.add(say(questions.get(questionID).toString()));

// Request recording of question
JSONObject action = new JSONObject();
JSONObject options = new JSONObject();
options.put("finishOnKey", "any"); // Finish either on key press or after 10 seconds of silence
options.put("timeout", 10);
options.put("onFinish", String.format("http://%s/callStep", req.host())); // Send recording to this same call flow URL
action.put("action", "record");
action.put("options", options);
steps.add(action);
```

The `record` step is configured so that it finishes when the caller presses any key on their phone's keypad (`finishOnKey` attribute) or when MessageBird detects 10 seconds of silence (`timeout` attribute). By specifying the URL with the onFinish attribute we can make sure that the recording data is sent back to our route and that we can send additional steps to the caller. Building the URL with protocol and hostname information from the request ensures that it works wherever the application is deployed and also behind the tunnel.

Only one tiny part remains: the last step in each webhook request is sending back a JSON response based on the `flow` object:

```java
res.type("application/json");
return flow;
```

## Building an Admin View

The survey application also contains an admin view that allows us to view the survey participants and listen to their responses. The implementation of the `get("/admin")` route is straightforward, it essentially loads everything from the database plus the questions data and adds it to the data available for a [Handlebars templates](http://sparkjava.com/documentation#views-and-templates).

The template, which you can see in `resources/templates/participants.handlebars`, contains a basic HTML structure with a three-column table. Inside the table, two nested loops over the participants and their responses add a line for each answer with the number of the caller, the question and a "Listen" button that plays it back.

Let's have a more detailed look at the implementation of this "Listen" button. On the frontend, the button calls a Javascript function called `playAudio()` with the `callId`, `legId` and `recordingId` inserted through ERB expressions:

``` HTML
<button onclick="playAudio('{{p.callId}}','{{this.legId}}','{{this.recordingId}}')">Listen</button>
```

The implementation of that function dynamically generates an invisible, auto-playing HTML5 audio element:

``` javascript
function playAudio(callId, legId, recordingId) {
    document.getElementById('audioplayer').innerHTML
        = '<audio autoplay="1"><source src="/play/' + callId
            + '/' + legId + '/' + recordingId
            + '" type="audio/wav"></audio>';
}
```

As you can see, the WAV audio is requested from a route of the survey application. This route acts as a proxy server that fetches the audio from MessageBird's API and forward it to the frontend. This architecture is necessary because we need a MessageBird API key to fetch the audio but don't want to expose it on the client-side of our application. We use request to make the API call and add the API key as an HTTP header:

```java
get("/play/*/*/*",
        (req, res) ->
        {
            File temp = File.createTempFile("temp-file", ".wav");

            URL url = new URL(String.format("https://voice.messagebird.com/calls/%s/legs/%s/recordings/%s.wav", req.splat()[0], req.splat()[1], req.splat()[2]));
            HttpURLConnection http = (HttpURLConnection) url.openConnection();

            byte[] wavFile = Files.readAllBytes(Paths.get(temp.getAbsolutePath()));

            http.setRequestMethod("POST");
            http.setRequestProperty("Authorization", String.format("AccessKey %s", dotenv.get("MESSAGEBIRD_API_KEY")));
            http.setRequestProperty("Content-Type", "audio/wav");
            http.setDoOutput(true);

            DataOutputStream request = new DataOutputStream(http.getOutputStream());
            request.write(wavFile);
            request.flush();
            request.close();

            res.type("*/*");
            res.raw().setContentLength(wavFile.length);

            res.status(200);

            final ServletOutputStream os = res.raw().getOutputStream();
            final ByteArrayInputStream in = new ByteArrayInputStream(wavFile);
            IOUtils.copy(in, os);
            in.close();
            os.close();

            return null;
        }
);
```

As you can see, the API key is taken from an environment variable. To provide the key in the environment variable, [dotenv](https://mvnrepository.com/artifact/io.github.cdimascio/java-dotenv) is used. We've prepared an env.example file in the repository, which you should rename to .env and add the required information. Here's an example:

```
MESSAGEBIRD_API_KEY=YOUR-API-KEY
```

You can create or retrieve a live API key from the [API access (REST) tab](https://dashboard.messagebird.com/en/developers/access) in the [Developers section](https://dashboard.messagebird.com/en/developers/settings) of your MessageBird account.

## Testing your Application

Check again that you have set up your number correctly with a flow to forward incoming phone calls to an ngrok URL and that the tunnel is still running. Remember, whenever you start a fresh tunnel, you'll get a new URL, so you have to update the flows accordingly.

To start the application, build and run the application through your IDE.

Now, take your phone and dial your survey number. You should hear the welcome message and the first question. Speak an answer and press any key. At that moment you should see some database debug output in the console. Open http://localhost:4567/admin to see your call as well. Continue interacting with the survey. In the end, you can refresh your browser and listen to all the answers you recorded within your phone call.

Congratulations, you just deployed a survey system with MessageBird!

## Supporting Outbound Calls

The application was designed for incoming calls where survey participants call a virtual number and can provide their answers. The same code works without any changes for an outbound call scenario as well. The only thing you have to do is to start a call through the API or other means and use a call flow that contains a `fetchCallFlow` step pointing to your webhook route.

## Nice work!

You now have a running integration of MessageBird's Voice API!

You can now leverage the flow, code snippets and UI examples from this tutorial to build your own automated voice survey. Don't forget to download the code from the [MessageBird Developer Tutorials GitHub repository](https://github.com/messagebirdguides/automated-surveys).

## Next steps 🎉

Want to build something similar but not quite sure how to get started? Please feel free to let us know at support@messagebird.com, we'd love to help!
