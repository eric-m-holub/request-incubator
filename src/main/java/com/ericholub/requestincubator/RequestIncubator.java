package com.ericholub.requestincubator;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.BurpExtension;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.persistence.PersistedObject;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


public class RequestIncubator implements BurpExtension, ContextMenuItemsProvider, ExtensionUnloadingHandler
{
    public MontoyaApi api;
    public PersistedObject persist;

    public ArrayList<RequestIncubatorRequest> incubatorRequests;
    private RequestIncubatorUI incubatorUI;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        persist = api.persistence().extensionData();
        this.incubatorRequests = new ArrayList<>();
        
        // Extension metadata
        api.extension().setName("Request Incubator");
        api.extension().registerUnloadingHandler(this);

        // Logging
        this.logToOutput("Request Incubator Loaded\nRight-click on HTTP requests: Extensions -> Request Incubator -> Incubate Request");

        api.userInterface().registerContextMenuItemsProvider(this);

        // Add a custom UI tab
        addUiTab(api.userInterface());

        this.loadSavedIncubatorRequests();
    }

    // Log output
    public void logToOutput(String output) {
        this.api.logging().logToOutput(output);
    }


    // Create UI tab in Burp Suite
    private void addUiTab(UserInterface ui) {
        incubatorUI = new RequestIncubatorUI(this);
        ui.registerSuiteTab("Request Incubator", incubatorUI);
    }


    // Add ability for users to right click HTTP calls and 'Incubate Request'
    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        if (event.isFromTool(ToolType.PROXY, ToolType.TARGET, ToolType.LOGGER, ToolType.REPEATER, ToolType.INTRUDER)) {
            HttpRequestResponse requestResponse = event.messageEditorRequestResponse().isPresent() ? event.messageEditorRequestResponse().get().requestResponse() : event.selectedRequestResponses().get(0);

            if (requestResponse.request() != null) {
                List<Component> menuItemList = new ArrayList<>();
                JMenuItem incubateRequestItem = new JMenuItem("Incubate Request");
                incubateRequestItem.addActionListener(l -> this.addNewIncubatorRequest(requestResponse.request()));
                menuItemList.add(incubateRequestItem);
                return menuItemList;
            }
    
        }

        return null;
    }

    @Override
    public void extensionUnloaded() {
        logToOutput("Unloading Request Incubator....");

        // Clear out exists saved Incubator Requests
        for (String key : persist.childObjectKeys()) {
            if (key.startsWith("IncubatorRequest:")) {
                persist.deleteChildObject(key);
            }
        }

        // Save current Incubator Requests
        for (int i=0; i<incubatorRequests.size(); i++) {
            RequestIncubatorRequest request = incubatorRequests.get(i);
            this.saveIncubatorRequest(request);
        }

        incubatorUI.unloadUI();
    }

    private void loadSavedIncubatorRequests() {

        ArrayList<String> keys = new ArrayList(persist.childObjectKeys());
        keys.sort(Comparator.comparingInt(k -> Integer.parseInt(k.split(":")[1])));

        for (String key : keys) {
            if (!key.startsWith("IncubatorRequest:")) {
                continue;
            }

            PersistedObject persistedIncubatorRequest = persist.getChildObject(key);
            if (persistedIncubatorRequest == null) {
                continue;
            }

            RequestIncubatorRequest loadedRequest = new RequestIncubatorRequest(persistedIncubatorRequest);
            incubatorRequests.add(loadedRequest);
            incubatorUI.addIncubatorRequest();
        }
    }

    // Save individual Incubator Request
    private void saveIncubatorRequest(RequestIncubatorRequest request) {
        PersistedObject persistedIncubatorRequest = PersistedObject.persistedObject();
    
        // Save HTTP request(s)
        persistedIncubatorRequest.setHttpRequest("request", request.request);

        // Save strings
        persistedIncubatorRequest.setString("host", request.host);
        persistedIncubatorRequest.setString("method", request.method);
        persistedIncubatorRequest.setString("path", request.path);
        persistedIncubatorRequest.setString("nickname", request.nickname);
        persistedIncubatorRequest.setString("frequencyMethod", request.frequencyMethod);
        persistedIncubatorRequest.setString("frequencyUnit", request.frequencyUnit);
        persistedIncubatorRequest.setString("errorCodeCondition", request.errorCodeCondition);
        persistedIncubatorRequest.setString("errorTextCondition", request.errorTextCondition);

        if (request.latestResponse != null) {
            persistedIncubatorRequest.setString("latestResponse", request.latestResponse.toString());
        }

        // Save ints
        persistedIncubatorRequest.setInteger("frequencyTime", request.frequencyTime);
        persistedIncubatorRequest.setInteger("frequencyTimeSec", request.frequencyTimeSec);
        persistedIncubatorRequest.setInteger("requestCount", request.requestCount);

        // Save bools
        persistedIncubatorRequest.setBoolean("statusCodeCheckFailed", request.statusCodeCheckFailed);
        persistedIncubatorRequest.setBoolean("textCheckFailed", request.textCheckFailed);

        persist.setChildObject("IncubatorRequest:" + String.valueOf(request.id), persistedIncubatorRequest);
    }

    private void addNewIncubatorRequest(HttpRequest request) {
        RequestIncubatorRequest incubatorRequest = new RequestIncubatorRequest(request);
        incubatorRequests.add(incubatorRequest);
        incubatorUI.addIncubatorRequest();
    }


    public HttpResponse makeHTTPRequest(HttpRequest request) {
        return this.api.http().sendRequest(request).response();
    }
  
}
