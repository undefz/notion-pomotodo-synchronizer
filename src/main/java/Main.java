import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    record Config(String pomodoroToken, String notionToken, String notionDatabase, String taskPrefix) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NotionTask(String uuid, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PomodoroTask(String uuid, String description, boolean completed) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient();
    private Config config;

    private void readConfig() {
        try {
            config = mapper.readValue(new File("config.json"), Config.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, NotionTask> readNotionTasks() {
        Request request = new Request.Builder()
                .url("https://api.notion.com/v1/databases/" + config.notionDatabase + "/query")
                .post(RequestBody.create("""
                        {
                            "filter": {
                                "property": "Status",
                                "select": {
                                    "equals": "In progress"
                                }
                            }
                        }
                        """, JSON))
                .addHeader("Authorization", "Bearer " + config.notionToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Notion-Version", "2021-08-16")
                .build();

        try (Response response = client.newCall(request).execute()) {
            var str = response.body().string();
            var json = mapper.readTree(str);

            var results = json.get("results");

            Map<String, NotionTask> notionTasks = new HashMap<>();

            for (var node : results) {
                var name = config.taskPrefix + node.get("properties").get("Name").get("title").get(0).get("plain_text").asText();
                var uuid = node.get("id").asText();
                notionTasks.put(name, new NotionTask(uuid, name));
            }

            return notionTasks;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, PomodoroTask> readPomoTodoTasks() {
        Map<String, PomodoroTask> result = new HashMap<>();

        result.putAll(readPomoTodoTasks(false));
        result.putAll(readPomoTodoTasks(true));

        return result;
    }

    private Map<String, PomodoroTask> readPomoTodoTasks(boolean completed) {
        Request request = new Request.Builder()
                .url("https://api.pomotodo.com/1/todos?completed=" + completed)
                .get()
                .addHeader("Authorization", "token " + config.pomodoroToken)
                .build();

        try (Response response = client.newCall(request).execute()) {
            var str = response.body().string();
            var pomoTasks = mapper.readValue(str, new TypeReference<List<PomodoroTask>>() {
            });

            return pomoTasks.stream().collect(Collectors.toMap(x -> x.description, x -> x));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    record UpdateList(Set<String> addToPomoTodo, Set<String> removeFromPomoTodo, Set<String> completeInNotion) {
    }

    private UpdateList formUpdateList(Map<String, NotionTask> notionTasks, Map<String, PomodoroTask> pomoTasks) {
        Set<String> toAdd = new HashSet<>();
        Set<String> toRemove = new HashSet<>();
        Set<String> toMarkCompleted = new HashSet<>();

        for (var notionTask: notionTasks.keySet()) {
            if (!pomoTasks.containsKey(notionTask)) {
                toAdd.add(notionTask);
            }
        }

        for (var pomoTask : pomoTasks.values()) {
            if (pomoTask.description.startsWith(config.taskPrefix)) {
                var notionTask = notionTasks.get(pomoTask.description);

                if (notionTask != null) {
                    if (pomoTask.completed) {
                        toMarkCompleted.add(notionTask.uuid);
                    }
                } else {
                    toRemove.add(pomoTask.uuid);
                }
            }
        }

        return new UpdateList(toAdd, toRemove, toMarkCompleted);
    }

    private void addPomoTask(String task) {
        var jsonRequest = String.format("""
                        {
                        	"description": "%s",
                        	"pin": true
                        }
                        """, task);

        Request request = new Request.Builder()
                .url("https://api.pomotodo.com/1/todos")
                .post(RequestBody.create(jsonRequest, JSON))
                .addHeader("Authorization", "token " + config.pomodoroToken)
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Added pomo task " + task + " with code " + response.code());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void removePomoTask(String task) {
        Request request = new Request.Builder()
                .url("https://api.pomotodo.com/1/todos/" + task)
                .delete()
                .addHeader("Authorization", "token " + config.pomodoroToken)
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Removed task " + task + " with code " + response.code());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void markNotionTaskCompleted(String uuid) {
        Request request = new Request.Builder()
                .url("https://api.notion.com/v1/pages/" + uuid)
                .patch(RequestBody.create("""
                        {
                        	"properties": {
                        		"Status": {
                        			"select": {
                        				"name": "Completed"
                        			}
                        		}
                        	}
                        }
                        """, JSON))
                .addHeader("Authorization", "Bearer " + config.notionToken)
                .addHeader("Content-Type", "application/json")
                .addHeader("Notion-Version", "2021-08-16")
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Completed task " + uuid + " code " + response.code());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void run() {
        readConfig();

        var notionTasks = readNotionTasks();
        var pomoTodoTasks = readPomoTodoTasks();

        var updateList = formUpdateList(notionTasks, pomoTodoTasks);

        updateList.addToPomoTodo.forEach(this::addPomoTask);
        updateList.removeFromPomoTodo.forEach(this::removePomoTask);
        updateList.completeInNotion.forEach(this::markNotionTaskCompleted);
    }

    public static void main(String[] args) {
        new Main().run();
    }
}
