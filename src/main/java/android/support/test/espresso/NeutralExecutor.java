package android.support.test.espresso;

import android.os.StrictMode;
import android.util.Log;

import org.json.rpc.client.JsonRpcInvoker;
import org.json.rpc.client.TcpRpcClientTransport;
import org.json.rpc.server.JsonRpcExecutor;
import org.json.rpc.server.TcpServerTransport;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;
import java.util.ArrayList;

import dk.au.cs.thor.rti.rtinterface.SchedulerInterface;
import dk.au.cs.thor.rti.rtinterface.SchedulerTestInterface;

public final class NeutralExecutor {

    public static List<NeutralComponent> neutralComponents = null;

    public static boolean handlingTestAction = false;

    private NeutralExecutor() {}

    public static void startServer() {
        if (client != null) {
            Log.v("Espresso", "Server already started");
            return;
        }

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        String portEmulatorServerStr = null;
        String portHostServerStr = null;

        try {
            Class clazz = Class.forName("android.os.SystemProperties");
            Method method = clazz.getDeclaredMethod("get", String.class);

            portEmulatorServerStr = (String) method.invoke(null, "port.emu");
            portHostServerStr = (String) method.invoke(null, "port.host");

            final int portEmulatorServer = !portEmulatorServerStr.equals("") ? Integer.parseInt(portEmulatorServerStr) : -1;
            final int portHostServer = !portHostServerStr.equals("") ? Integer.parseInt(portHostServerStr) : -1;

            if (portEmulatorServer >= 0 && portHostServer >= 0) {
                // Establish connection to scheduler, such that we can call methods there (does not actually connect here...)
                client = new TcpRpcClientTransport(new URL("http://10.0.2.2:" + portHostServer));

                // Set-up the server
                Log.i("Espresso", "Setting up the server");

                Thread serverThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        JsonRpcExecutor executor = new JsonRpcExecutor();
                        executor.addHandler("rti", rti, SchedulerTestInterface.class);

                        TcpServerTransport server = new TcpServerTransport(portEmulatorServer);

                        while (client != null) {
                            executor.execute(server);
                            server.closeClient();
                        }

                        server.closeServer();

                        Log.i("Espresso", "Server shutting down");
                    }
                });

                serverThread.start();

                // Send a ready message to the scheduler
                try {
                    Log.i("Espresso", "Sending message Ready to the scheduler");
                    getSchedulerInterface().ready();
                } catch (Exception e) {
                    Log.e("Espresso", "Scheduler not running?", e);
                    client = null;
                }

                if (client != null) {
                    // Wait for the scheduler to start the test
                    int waits = 0;
                    while (!rti.started) {
                        waits++;

                        if (waits >= 10) {
                            Log.e("Espresso", "The scheduler did not start the test within 2.5 seconds");
                            break;
                        }
                        Thread.sleep(250);
                    }
                }
            }
        } catch(Exception e) {
            Log.e("Espresso", "Error connecting to the scheduler (port: " + portHostServerStr + ")", e);
        }

        Log.i("Espresso", "Starting test");
    }

    public static void stopServer() {
        client = null;
    }

    public static SchedulerInterface getSchedulerInterface() {
        if (client != null) {
            JsonRpcInvoker invoker = new JsonRpcInvoker();
            return invoker.get(client, "scheduler", SchedulerInterface.class);
        }
        return null;
    }

    public static void notifyAtInjectionSite(ViewAction viewAction) {
        SchedulerInterface scheduler = NeutralExecutor.getSchedulerInterface();
        if (scheduler != null) {
            Log.v("Espresso", "ViewInteraction.doPerform(" + viewAction + "): notifying scheduler");
            String[] activeNeutralComponentNames = null;

            List<NeutralComponent> activeNeutralComponents = NeutralExecutor.getActiveNeutralComponents();
            if (activeNeutralComponents != null) {
                activeNeutralComponentNames = new String[activeNeutralComponents.size()];

                for (int i = 0; i < activeNeutralComponents.size(); i++) {
                    NeutralComponent component = activeNeutralComponents.get(i);
                    activeNeutralComponentNames[i] = component.getName();
                }
            }

            scheduler.atInjectionSite(viewAction.toString(), activeNeutralComponentNames);

            Log.v("Espresso", "ViewInteraction: ... success");
        }
    }

    static NeutralComponent getNeutralComponentByName(String name) {
        if (neutralComponents != null) {
            for (NeutralComponent component : neutralComponents) {
                if (component.getClass().getName().equals(name)) {
                    return component;
                }
            }
        }

        return null;
    }

    static List<NeutralComponent> getActiveNeutralComponents() {
        List<NeutralComponent> activeNeutralComponent = null;

        if (neutralComponents != null) {
            activeNeutralComponent = new ArrayList<NeutralComponent>();

            for (NeutralComponent component : neutralComponents) {
                if (component.canExecute()) {
                    activeNeutralComponent.add(component);
                }
            }
        }

        return activeNeutralComponent;
    }


    public static final RtiImplementation rti = new RtiImplementation();
    public static TcpRpcClientTransport client = null;

    private static class RtiImplementation implements SchedulerTestInterface {
        public boolean started = false;

        @Override
        public void start() {
            Log.i("Espresso", "Received message Start from the scheduler");
            started = true;
        }

        @Override
        public void loadNeutralComponents(String[] neutralComponentNames) {
            Log.i("Espresso", "Received message LoadNeutralComponents from the scheduler");
            List<NeutralComponent> neutralComponents = new ArrayList<NeutralComponent>();

            for (String name : neutralComponentNames) {
                try {
                    Class<?> c = Class.forName(name);
                    neutralComponents.add((NeutralComponent) c.newInstance());
                } catch (Exception e) {
                    Log.e("Espresso", "Unable to load neutral component: " + name, e);
                }
            }

            NeutralExecutor.neutralComponents = neutralComponents;
        }

        @Override
        public void executeNeutralComponents(String[] neutralComponentNames) {
            Log.i("Espresso", "Received message ExecuteNeutralComponents from the scheduler");
            for (String name : neutralComponentNames) {
                try {
                    NeutralComponent component = NeutralExecutor.getNeutralComponentByName(name);
                    if (component != null) {
                        component.execute();
                    } else {
                        Log.e("Espresso", "Unable to find neutral component: " + name);
                    }
                } catch (Exception e) {
                    Log.e("Espresso", "Unable to execute neutral component: " + name, e);
                }
            }
        }
    }
}
