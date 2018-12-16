import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Updates;
import io.github.cdimascio.dotenv.Dotenv;
import org.bson.Document;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import spark.ModelAndView;
import spark.Request;
import spark.Response;
import spark.template.handlebars.HandlebarsTemplateEngine;
import spark.utils.IOUtils;

import javax.servlet.ServletOutputStream;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;
import static spark.Spark.get;
import static spark.Spark.post;


public class AutomatedVoiceSurvey {
    // Helper function to generate a "say" call flow step.
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

    public static JSONObject handleCallStep(Request req, Response res, MongoClient mongoClient, JSONArray questions) {
        // Prepare a Call Flow that can be extended
        JSONObject flow = new JSONObject();
        JSONArray steps = new JSONArray();
        flow.put("title", "Survey Call Step");

        MongoDatabase database = mongoClient.getDatabase("myproject");
        MongoCollection<Document> collection = database.getCollection("survey_participants");

        String callID = req.queryParams("callID");
        Document doc = collection.find(eq("callId", callID)).first();

        // Determine the next question
        Integer questionID = 0;

        if (doc != null) {
            List<Document> responses = (List<Document>) doc.get("responses");
            questionID = responses.size() + 1;
        } else {
            // Create new participant database entry
            Document entry = new Document("callId", req.queryParams("callID"))
                    .append("number", req.queryParams("destination"))
                    .append("responses", new JSONArray());
            collection.insertOne(entry);
        }

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

        if (questionID == questions.size()) {
            // All questions have been answered
            steps.add(say("You have completed our survey. Thank you for participating!"));
        } else {
            if (questionID == 0) {
                // Before first question, say welcome
                steps.add(say(String.format("Welcome to our survey! You will be asked %s questions. The answers will be recorded. Speak your response for each and press any key on your phone to move on to the next question. Here is the first question:", questions.size())));
            }

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
        }


        flow.put("steps", steps);

        res.type("application/json");
        return flow;
    }

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.load();

        MongoClient mongoClient = MongoClients.create("mongodb://localhost:27017");

        ClassLoader classLoader = new AutomatedVoiceSurvey().getClass().getClassLoader();

        JSONParser parser = new JSONParser();

        try {
            File questionsFile = new File(classLoader.getResource("questions.json").getFile());
            final JSONArray questions = (JSONArray) parser.parse(
                    new FileReader(questionsFile));

            get("/callStep", (req, res) -> handleCallStep(req, res, mongoClient, questions));
            post("/callStep", (req, res) -> handleCallStep(req, res, mongoClient, questions));

            get("/admin",
                    (req, res) ->
                    {

                        MongoDatabase database = mongoClient.getDatabase("myproject");
                        MongoCollection<Document> collection = database.getCollection("survey_participants");
                        List<Document> docs = new ArrayList<>();
                        collection.find().into(docs);

                        Map<String, Object> model = new HashMap<>();
                        model.put("questions", questions.toArray());
                        model.put("participants", docs.toArray());
                        return new ModelAndView(model, "participants.handlebars");
                    },

                    new HandlebarsTemplateEngine()
            );
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }

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
    }
}