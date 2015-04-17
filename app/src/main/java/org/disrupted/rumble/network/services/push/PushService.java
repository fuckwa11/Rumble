package org.disrupted.rumble.network.services.push;

import android.util.Log;

import org.disrupted.rumble.app.RumbleApplication;
import org.disrupted.rumble.database.DatabaseExecutor;
import org.disrupted.rumble.database.DatabaseFactory;
import org.disrupted.rumble.database.PushStatusDatabase;
import org.disrupted.rumble.database.events.StatusDeletedEvent;
import org.disrupted.rumble.database.events.StatusInsertedEvent;
import org.disrupted.rumble.database.objects.Contact;
import org.disrupted.rumble.database.objects.Group;
import org.disrupted.rumble.database.objects.InterestVector;
import org.disrupted.rumble.database.objects.PushStatus;
import org.disrupted.rumble.network.events.NeighbourConnected;
import org.disrupted.rumble.network.events.NeighbourDisconnected;
import org.disrupted.rumble.network.protocols.ProtocolWorker;
import org.disrupted.rumble.network.protocols.command.CommandSendLocalInformation;
import org.disrupted.rumble.network.protocols.command.CommandSendPushStatus;
import org.disrupted.rumble.network.protocols.rumble.RumbleProtocol;
import org.disrupted.rumble.util.HashUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import de.greenrobot.event.EventBus;

/**
 * @author Marlinski
 */
public class PushService {

    private static final String TAG = "PushService";

    private static final Object lock = new Object();
    private static PushService instance;

    private static ReplicationDensityWatcher rdwatcher;
    private static final Random random = new Random();

    private static Map<String, MessageDispatcher> workerIdentifierTodispatcher;

    private PushService() {
        rdwatcher = new ReplicationDensityWatcher(1000*3600);
    }

    public static void startService() {
        if(instance != null)
            return;

        synchronized (lock) {
            Log.d(TAG, "[.] Starting PushService");
            if (instance == null) {
                instance = new PushService();
                rdwatcher.start();
                workerIdentifierTodispatcher = new HashMap<String, MessageDispatcher>();
                EventBus.getDefault().register(instance);
            }
        }
    }

    public static void stopService() {
        if(instance == null)
                return;
        synchronized (lock) {
            Log.d(TAG, "[-] Stopping PushService");
            if(EventBus.getDefault().isRegistered(instance))
                EventBus.getDefault().unregister(instance);

            for(Map.Entry<String, MessageDispatcher> entry : instance.workerIdentifierTodispatcher.entrySet()) {
                MessageDispatcher dispatcher = entry.getValue();
                dispatcher.interrupt();
            }
            instance.workerIdentifierTodispatcher.clear();
            rdwatcher.stop();
            instance = null;
        }
    }

    // todo: register protocol to service
    public void onEvent(NeighbourConnected neighbour) {
        if(instance != null) {
            if(!neighbour.worker.getProtocolIdentifier().equals(RumbleProtocol.protocolID))
                return;
            synchronized (lock) {
                MessageDispatcher dispatcher = instance.workerIdentifierTodispatcher.get(neighbour.worker.getWorkerIdentifier());
                if (dispatcher != null) {
                    Log.e(TAG, "worker already binded ?!");
                    return;
                }
                dispatcher = new MessageDispatcher(neighbour.worker);
                instance.workerIdentifierTodispatcher.put(neighbour.worker.getWorkerIdentifier(), dispatcher);
                dispatcher.startDispatcher();
            }
        }
    }

    public void onEvent(NeighbourDisconnected neighbour) {
        if(instance != null) {
            if(!neighbour.worker.getProtocolIdentifier().equals(RumbleProtocol.protocolID))
                return;
            synchronized (lock) {
                MessageDispatcher dispatcher = instance.workerIdentifierTodispatcher.get(neighbour.worker.getWorkerIdentifier());
                if (dispatcher == null)
                    return;
                dispatcher.stopDispatcher();
                instance.workerIdentifierTodispatcher.remove(neighbour.worker.getWorkerIdentifier());
            }
        }
    }

    private static float computeScore(PushStatus message, InterestVector interestVector) {
        //todo InterestVector for relevance
        float relevance = 0;
        float replicationDensity = rdwatcher.computeMetric(message.getUuid());
        float quality =  (message.getDuplicate() == 0) ? 0 : (float)message.getLike()/(float)message.getDuplicate();
        float age = (message.getTTL() <= 0) ? 1 : (1- (System.currentTimeMillis() - message.getTimeOfCreation())/message.getTTL());
        boolean distance = true;

        float a = 0;
        float b = (float)0.6;
        float c = (float)0.4;

        float score = (a*relevance + b*replicationDensity + c*quality)*age*(distance ? 1 : 0);

        return score;
    }

    // todo: not being dependant on age would make it so much easier ....
    private static class MessageDispatcher extends Thread {

        private static final String TAG = "MessageDispatcher";

        private ProtocolWorker worker;
        private InterestVector interestVector;
        private ArrayList<Integer> statuses;
        private float threshold;

        // locks for managing the ArrayList
        private final ReentrantLock putLock = new ReentrantLock(true);
        private final ReentrantLock takeLock = new ReentrantLock(true);
        private final Condition notEmpty = takeLock.newCondition();

        private boolean running;

        private PushStatus max;

        private void fullyLock() {
            putLock.lock();
            takeLock.lock();
        }
        private void fullyUnlock() {
            putLock.unlock();
            takeLock.unlock();
        }
        private void signalNotEmpty() {
            final ReentrantLock takeLock = this.takeLock;
            takeLock.lock();
            try {
                notEmpty.signal();
            } finally {
                takeLock.unlock();
            }
        }

        public MessageDispatcher(ProtocolWorker worker) {
            this.running = false;
            this.worker = worker;
            this.max = null;
            this.threshold = 0;
            this.interestVector = null;
            statuses = new ArrayList<Integer>();
        }

        public void startDispatcher() {
            running = true;
            sendLocalPreferences();
            initStatuses();
        }

        public void stopDispatcher() {
            running = false;
            this.interrupt();
            worker = null;
            if(EventBus.getDefault().isRegistered(this))
                EventBus.getDefault().unregister(this);
        }

        private void initStatuses() {
            PushStatusDatabase.StatusQueryOption options = new PushStatusDatabase.StatusQueryOption();
            options.filterFlags |= PushStatusDatabase.StatusQueryOption.FILTER_GROUP;
            options.groupIDList = new ArrayList<String>();
            options.groupIDList.add(Group.getDefaultGroup().getGid());
            options.filterFlags |= PushStatusDatabase.StatusQueryOption.FILTER_NEVER_SEND_TO_USER;
            options.peerName = HashUtil.computeInterfaceID(
                    worker.getLinkLayerConnection().getRemoteLinkLayerAddress(),
                    worker.getProtocolIdentifier());
            options.query_result = PushStatusDatabase.StatusQueryOption.QUERY_RESULT.LIST_OF_IDS;
            DatabaseFactory.getPushStatusDatabase(RumbleApplication.getContext()).getStatuses(options, onStatusLoaded);
        }
        DatabaseExecutor.ReadableQueryCallback onStatusLoaded = new DatabaseExecutor.ReadableQueryCallback() {
            @Override
            public void onReadableQueryFinished(Object result) {
                if (result != null) {
                    final ArrayList<Integer> answer = (ArrayList<Integer>)result;
                    for (Integer s : answer) {
                        PushStatus message = DatabaseFactory.getPushStatusDatabase(RumbleApplication.getContext())
                                .getStatus(s);
                        if(message != null) {
                            add(message);
                            message.discard();
                        }
                    }
                    EventBus.getDefault().register(MessageDispatcher.this);
                    start();
                }
            }
        };

        @Override
        public void run() {
            try {
                Log.d(TAG, "[+] MessageDispatcher initiated");
                do {
                    // pickup a message and send it to the CommandExecutor
                    if (worker != null) {
                        PushStatus message = pickMessage();
                        worker.execute(new CommandSendPushStatus(message));
                        message.discard();
                        //todo just for the sake of debugging
                        sleep(1000, 0);
                    }

                } while (running);

            } catch (InterruptedException ie) {
            } finally {
                clear();
                Log.d(TAG, "[-] MessageDispatcher stopped");
            }
        }

        private void clear() {
            fullyLock();
            try {
                if(EventBus.getDefault().isRegistered(this))
                    EventBus.getDefault().unregister(this);
                statuses.clear();
            } finally {
                fullyUnlock();
            }
        }

        private boolean add(PushStatus message){
            final ReentrantLock putlock = this.putLock;
            try {
                putlock.lock();

                float score = computeScore(message, interestVector);

                if (score <= threshold) {
                    message.discard();
                    return false;
                }
                statuses.add((int)message.getdbId());

                if (max == null) {
                    max = message;
                } else {
                    float maxScore = computeScore(max, interestVector);
                    if (score > maxScore) {
                        max.discard();
                        max = message;
                    } else
                        message.discard();
                }

                signalNotEmpty();
                return true;
            } finally {
                putlock.unlock();
            }
        }

        // todo: iterating over the entire array, the complexity is DAMN TOO HIGH !!
        private void updateMax() {
            float maxScore = 0;
            if(max != null) {
                maxScore = computeScore(max, interestVector);
                if(maxScore > threshold)
                    return;
            }

            ArrayList<Integer> toDelete = new ArrayList<Integer>();
            Iterator<Integer> it = statuses.iterator();
            while(it.hasNext()) {
                Integer id = it.next();
                PushStatus message = DatabaseFactory.getPushStatusDatabase(RumbleApplication.getContext())
                        .getStatus(id);
                float score = computeScore(max, interestVector);
                if(score <= threshold) {
                    message.discard();
                    toDelete.add((int)message.getdbId());
                    continue;
                }

                if(max == null) {
                    max = message;
                    maxScore = score;
                    continue;
                }

                if(score > maxScore) {
                    max.discard();
                    max = message;
                    maxScore = score;
                } else
                    message.discard();
            }

            for(Integer i : toDelete) {
                statuses.remove(new Integer(i));
            }

        }

        /*
         *  See the paper:
         *  "Roulette-wheel selection via stochastic acceptance"
         *  By Adam Lipowski, Dorota Lipowska
         */
        private PushStatus pickMessage() throws InterruptedException {
            final ReentrantLock takelock = this.takeLock;
            final ReentrantLock putlock = this.takeLock;
            PushStatus message;
            boolean pickup = false;
            takelock.lockInterruptibly();
            try {
                do {
                    while (statuses.size() == 0)
                        notEmpty.await();

                    Log.d(TAG, "pick");
                    for(Integer id:statuses) {
                        Log.d(TAG, ","+id);
                    }
                    putlock.lock();
                    try {
                        updateMax();

                        // randomly pickup an element homogeneously
                        int index = random.nextInt(statuses.size());
                        long id = statuses.get(index);
                        message = DatabaseFactory.getPushStatusDatabase(RumbleApplication.getContext()).getStatus(id);
                        if(message == null) {
                            //Log.d(TAG, "cannot retrieve statusId: "+id);
                            statuses.remove(new Integer((int)id));
                            continue;
                        }

                        // get max probability Pmax
                        float maxScore = computeScore(max, interestVector);
                        // get element probability Pu
                        float score = computeScore(message, interestVector);

                        if (score <= threshold) {
                            //Log.d(TAG, "score too low: "+score);
                            statuses.remove(new Integer((int)id));
                            message.discard();
                            message = null;
                            continue;
                        }

                        int shallwepick = random.nextInt((int) (maxScore * 1000));
                        if (shallwepick <= (score * 1000)) {
                            //Log.d(TAG, "we picked up: "+id);
                            // we keep this status with probability Pu/Pmax
                            statuses.remove(new Integer((int)message.getdbId()));
                            pickup = true;
                        } else {
                            // else we pick another one
                            message.discard();
                        }
                    } finally {
                        putlock.unlock();
                    }
                } while(!pickup);
            } finally {
                takelock.unlock();
            }
            return message;
        }

        public void sendLocalPreferences() {
            Contact local = Contact.getLocalContact();
            int flags = Contact.FLAG_TAG_INTEREST | Contact.FLAG_GROUP_LIST;
            CommandSendLocalInformation command = new CommandSendLocalInformation(local,flags);
            local.toString();
            worker.execute(command);
        }
        public void onEvent(StatusDeletedEvent event) {
            fullyLock();
            try {
                statuses.remove(Integer.valueOf((int) event.dbid));
            } finally {
                fullyUnlock();
            }
        }
        public void onEvent(StatusInsertedEvent event) {
            PushStatus message = new PushStatus(event.status);
            add(message);
            message.discard();
        }
    }
}
