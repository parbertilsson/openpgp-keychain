package org.thialfihar.android.apg.utils;

import java.util.ArrayList;

import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import org.thialfihar.android.apg.IApgService;

/**
 * This class can be used by other projects to simplify connecting to the
 * APG-Service. Kind of wrapper of for AIDL.
 * 
 * It is not used in this project.
 */
public class ApgCon {

    private class call_async extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... arg) {
            Log.d(TAG, "Async execution starting");
            call(arg[0]);
            return null;
        }

        protected void onPostExecute(Void result) {
            Log.d(TAG, "Async execution finished");
            async_running = false;
            if (callback_object != null && callback_method != null) {
                try {
                    Log.d(TAG, "About to execute callback");
                    callback_object.getClass().getMethod(callback_method).invoke(callback_object);
                    Log.d(TAG, "Callback executed");
                } catch (NoSuchMethodException e) {
                    Log.w(TAG, "Exception in callback: Method '" + callback_method + "' not found");
                    warning_list.add("(LOCAL) Could not execute callback, method '" + callback_method + "' not found");
                } catch (Exception e) {
                    Log.w(TAG, "Exception on callback: (" + e.getClass() + ") " + e.getMessage());
                    warning_list.add("(LOCAL) Could not execute callback (" + e.getClass() + "): " + e.getMessage());
                }
            }
        }

    }

    private final static String TAG = "ApgCon";
    private final static int api_version = 1; // aidl api-version it expects

    private final Context mContext;
    private boolean async_running = false;
    private Object callback_object;
    private String callback_method;

    private final Bundle result = new Bundle();
    private final Bundle args = new Bundle();
    private final ArrayList<String> error_list = new ArrayList<String>();
    private final ArrayList<String> warning_list = new ArrayList<String>();
    private error local_error;

    /** Remote service for decrypting and encrypting data */
    private IApgService apgService = null;

    /** Set apgService accordingly to connection status */
    private ServiceConnection apgConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "IApgService bound to apgService");
            apgService = IApgService.Stub.asInterface(service);
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "IApgService disconnected");
            apgService = null;
        }
    };

    public static enum error {
        GENERIC, // no special type
        CANNOT_BIND_TO_APG, // connection to apg service not possible
        CALL_MISSING, // function to call not provided
        CALL_NOT_KNOWN, // apg service does not know what to do
        APG_NOT_FOUND, // could not find APG installed
        APG_AIDL_MISSING, // found APG but without AIDL interface
    }

    public static enum ret {
        ERROR, // returned from AIDL
        RESULT, // returned from AIDL
        WARNINGS, // mixed AIDL and LOCAL
        ERRORS, // mixed AIDL and LOCAL
        LOCAL_ERROR, // LOCAL error
    }

    /**
     * Constructor
     * 
     * <p>
     * Creates a new ApgCon object and searches for the right APG version on
     * initialization. If not found, errors are printed to the error log.
     * </p>
     * 
     * @param ctx
     *            the running context
     */
    public ApgCon(Context ctx) {
        Log.v(TAG, "EncryptionService created");
        mContext = ctx;

        try {
            Log.v(TAG, "Searching for the right APG version");
            ServiceInfo apg_services[] = ctx.getPackageManager().getPackageInfo("org.thialfihar.android.apg",
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA).services;
            if (apg_services == null) {
                Log.e(TAG, "Could not fetch services");
            } else {
                boolean apg_service_found = false;
                for (ServiceInfo inf : apg_services) {
                    Log.v(TAG, "Found service of APG: " + inf.name);
                    if (inf.name.equals("org.thialfihar.android.apg.ApgService")) {
                        apg_service_found = true;
                        if (inf.metaData == null) {
                            Log.w(TAG, "Could not determine ApgService API");
                            Log.w(TAG, "This probably won't work!");
                            warning_list.add("(LOCAL) Could not determine ApgService API");
                        } else if (inf.metaData.getInt("api_version") != api_version) {
                            Log.w(TAG, "Found ApgService API version" + inf.metaData.getInt("api_version") + " but exspected " + api_version);
                            Log.w(TAG, "This probably won't work!");
                            warning_list.add("(LOCAL) Found ApgService API version" + inf.metaData.getInt("api_version") + " but exspected " + api_version);
                        } else {
                            Log.v(TAG, "Found api_version " + api_version + ", everything should work");
                        }
                    }
                }

                if (!apg_service_found) {
                    Log.e(TAG, "Could not find APG with AIDL interface, this probably won't work");
                    error_list.add("(LOCAL) Could not find APG with AIDL interface, this probably won't work");
                    local_error = error.APG_AIDL_MISSING;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not find APG, is it installed?");
            error_list.add("(LOCAL) Could not find APG, is it installed?");
            local_error = error.APG_NOT_FOUND;
        }
    }

    /** try to connect to the apg service */
    private boolean connect() {
        Log.v(TAG, "trying to bind the apgService to context");

        if (apgService != null) {
            Log.v(TAG, "allready connected");
            return true;
        }

        try {
            mContext.bindService(new Intent(IApgService.class.getName()), apgConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.v(TAG, "could not bind APG service");
            return false;
        }

        int wait_count = 0;
        while (apgService == null && wait_count++ < 15) {
            Log.v(TAG, "sleeping 1 second to wait for apg");
            android.os.SystemClock.sleep(1000);
        }

        if (wait_count >= 15) {
            Log.v(TAG, "slept waiting for nothing!");
            return false;
        }

        return true;
    }

    /**
     * Disconnects ApgCon from Apg
     * 
     * <p>
     * This should be called whenever all work with APG is done (e.g. everything
     * you wanted to encrypt is encrypted), since connections with AIDL should
     * not be upheld indefinitely.
     * <p>
     * 
     * <p>
     * Also, if you destroy you end using your ApgCon-instance, this must be
     * called or else the connection to APG is leaked
     * </p>
     */
    public void disconnect() {
        Log.v(TAG, "disconnecting apgService");
        if (apgService != null) {
            mContext.unbindService(apgConnection);
            apgService = null;
        }
    }

    private boolean initialize() {
        if (apgService == null) {
            if (!connect()) {
                Log.v(TAG, "connection to apg service failed");
                return false;
            }
        }
        return true;
    }

    /**
     * Calls a function from APG's AIDL-interface
     * 
     * <p>
     * After you have set up everything with {@link #set_arg(String, String)}
     * (and variants), you can call a function from the AIDL-interface. This
     * will
     * <ul>
     * <li>start connection to the remote interface (if not already connected)</li>
     * <li>call the passed function with all set up parameters synchronously</li>
     * <li>set up everything to retrieve the result and/or warnings/errors</li>
     * </ul>
     * </p>
     * 
     * <p>
     * Note your thread will be blocked during execution - if you want to call
     * the function asynchronously, see {@link #call_async(String)}.
     * </p>
     * 
     * @param function
     *            a remote function to call
     * @return true, if call successful (= no errors), else false
     * 
     * @see #call_async(String)
     * @see #set_arg(String, String)
     */
    public boolean call(String function) {
        return this.call(function, args, result);
    }

    /**
     * Calls a function from remote interface asynchronously
     * 
     * <p>
     * This does exactly the same as {@link #call(String)}, but asynchronously.
     * While connection to APG and work are done in background, your thread can
     * go on executing.
     * <p>
     * 
     * <p>
     * To see whether the task is finished, you have to possibilities:
     * <ul>
     * <li>In your thread, poll {@link #is_running()}</li>
     * <li>Supply a callback with {@link #set_callback(Object, String)}</li>
     * </ul>
     * </p>
     * 
     * @param function
     *            a remote function to call
     * 
     * @see #call(String)
     * @see #is_running()
     * @see #set_callback(Object, String)
     */
    public void call_async(String function) {
        async_running = true;
        new call_async().execute(function);
    }

    private boolean call(String function, Bundle pArgs, Bundle pReturn) {

        error_list.clear();
        warning_list.clear();

        if (!initialize()) {
            error_list.add("(LOCAL) Cannot bind to ApgService");
            local_error = error.CANNOT_BIND_TO_APG;
            return false;
        }

        if (function == null || function.length() == 0) {
            error_list.add("(LOCAL) Function to call missing");
            local_error = error.CALL_MISSING;
            return false;
        }

        try {
            Boolean success = (Boolean) IApgService.class.getMethod(function, Bundle.class, Bundle.class).invoke(apgService, pArgs, pReturn);
            error_list.addAll(pReturn.getStringArrayList(ret.ERRORS.name()));
            warning_list.addAll(pReturn.getStringArrayList(ret.WARNINGS.name()));
            pReturn.remove(ret.ERRORS.name());
            pReturn.remove(ret.WARNINGS.name());
            return success;
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Remote call not known (" + function + "): " + e.getMessage());
            error_list.add("(LOCAL) Remote call not known (" + function + "): " + e.getMessage());
            local_error = error.CALL_NOT_KNOWN;
            return false;
        } catch (Exception e) {
            Log.e(TAG,  "Generic error (" + e.getClass() + "): " + e.getMessage());
            error_list.add("(LOCAL) Generic error (" + e.getClass() + "): " + e.getMessage());
            local_error = error.GENERIC;
            return false;
        }

    }

    /**
     * Set a string argument for APG
     * 
     * <p>
     * This defines a string argument for APG's AIDL-interface.
     * </p>
     * 
     * <p>
     * To know what key-value-pairs are possible (or required), take a look into
     * the IApgService.aidl
     * </p>
     * 
     * <p>
     * Note, that the parameters are not deleted after a call, so you have to
     * reset ({@link #clear_args()}) them manually if you want to.
     * </p>
     * 
     * 
     * @param key
     *            the key
     * @param val
     *            the value
     * 
     * @see #clear_args()
     */
    public void set_arg(String key, String val) {
        args.putString(key, val);
    }

    /**
     * Set a string-array argument for APG
     * 
     * <p>
     * If the AIDL-parameter is an {@literal ArrayList<String>}, you have to use
     * this function.
     * </p>
     * 
     * <code>
     * <pre>
     * set_arg("a key", new String[]{ "entry 1", "entry 2" });
     * </pre>
     * </code>
     * 
     * @param key
     *            the key
     * @param vals
     *            the value
     * 
     * @see #set_arg(String, String)
     */
    public void set_arg(String key, String vals[]) {
        ArrayList<String> list = new ArrayList<String>();
        for (String val : vals) {
            list.add(val);
        }
        args.putStringArrayList(key, list);
    }

    /**
     * Set up a boolean argument for APG
     * 
     * @param key
     *            the key
     * @param vals
     *            the value
     * 
     * @see #set_arg(String, String)
     */
    public void set_arg(String key, boolean val) {
        args.putBoolean(key, val);
    }

    /**
     * Set up a int argument for APG
     * 
     * @param key
     *            the key
     * @param vals
     *            the value
     * 
     * @see #set_arg(String, String)
     */
    public void set_arg(String key, int val) {
        args.putInt(key, val);
    }

    /**
     * Set up a int-array argument for APG
     * <p>
     * If the AIDL-parameter is an {@literal ArrayList<Integer>}, you have to
     * use this function.
     * </p>
     * 
     * @param key
     *            the key
     * @param vals
     *            the value
     * 
     * @see #set_arg(String, String)
     */
    public void set_arg(String key, int vals[]) {
        ArrayList<Integer> list = new ArrayList<Integer>();
        for (int val : vals) {
            list.add(val);
        }
        args.putIntegerArrayList(key, list);
    }

    /**
     * Clears all arguments
     * 
     * <p>
     * Anything the has been set up with the various
     * {@link #set_arg(String, String)} functions, is cleared.
     * </p>
     * <p>
     * Note, that any warning, error, callback, result etc. is not cleared with
     * this.
     * </p>
     * 
     * @see #reset()
     */
    public void clear_args() {
        args.clear();
    }

    /**
     * Return the object associated with the key
     * 
     * @param key
     *            the object's key you want to return
     * @return an object at position key, or null if not set
     */
    public Object get_arg(String key) {
        return args.get(key);
    }

    /**
     * Iterates through the errors
     * 
     * <p>
     * With this method, you can iterate through all errors. The errors are only
     * returned once and deleted immediately afterwards, so you can only return
     * each error once.
     * </p>
     * 
     * @return a human readable description of a error that happened, or null if
     *         no more errors
     * 
     * @see #has_next_error()
     * @see #clear_errors()
     */
    public String get_next_error() {
        if (error_list.size() != 0)
            return error_list.remove(0);
        else
            return null;
    }

    /**
     * Check, if there are any new errors
     * 
     * @return true, if there are unreturned errors, false otherwise
     * 
     * @see #get_next_error()
     */
    public boolean has_next_error() {
        return error_list.size() != 0;
    }

    /**
     * Returns the type of error happened
     * 
     * <p>
     * Currently, two error types are possible:
     * <ul>
     * <li>ret.LOCAL_ERROR: An error that happened on the caller site. This
     * might be something like connection to AIDL not possible or the funciton
     * call not know by AIDL. This means, the instance is not set up correctly
     * or prerequisites to use APG with AIDL are not met.</li>
     * <li>ret.ERROR: Connection to APG was successful, and the call started but
     * failed. Mostly this is because of wrong or missing parameters for APG.</li>
     * </ul>
     * </p>
     * 
     * @return the type of error that happend: ret.LOCAL_ERROR or ret.ERROR, or
     *         null if none happend
     */
    public ret get_error_type() {
        if (local_error != null) {
            return ret.LOCAL_ERROR;
        } else if (result.containsKey(ret.ERROR.name())) {
            return ret.ERROR;
        } else {
            return null;
        }
    }

    public error get_local_error() {
        return local_error;
    }

    public void clear_local_error() {
        local_error = null;
    }

    public int get_remote_error() {
        if (result.containsKey(ret.ERROR.name())) {
            return result.getInt(ret.ERROR.name());
        } else {
            return -1;
        }
    }

    public void clear_remote_error() {
        result.remove(ret.ERROR.name());
    }

    /**
     * Iterates through the warnings
     * 
     * <p>
     * With this method, you can iterate through all warnings. The warnings are
     * only returned once and deleted immediately afterwards, so you can only
     * return each warning once.
     * </p>
     * 
     * @return a human readable description of a warning that happened, or null
     *         if no more warnings
     * 
     * @see #has_next_warning()
     * @see #clear_warnings()
     */
    public String get_next_warning() {
        if (warning_list.size() != 0)
            return warning_list.remove(0);
        else
            return null;
    }

    /**
     * Check, if there are any new warnings
     * 
     * @return true, if there are unreturned warnings, false otherwise
     * 
     * @see #get_next_warning()
     */
    public boolean has_next_warning() {
        return warning_list.size() != 0;
    }

    /**
     * Get the result
     * 
     * <p>
     * This gets your result. After doing anything with APG, you get the output
     * with this function
     * </p>
     * <p>
     * Note, that when your last remote call is unsuccessful, the result will
     * still have the same value like the last successful call (or null, if no
     * call was successful). To ensure you do not work with old call's results,
     * either be sure to {@link #reset()} (or at least {@link #clear_result()})
     * your instance before each new call or always check that
     * {@link #has_next_error()} is false.
     * </p>
     * 
     * @return the result of the last {@link #call(String)} or
     *         {@link #call_asinc(String)}.
     * 
     * @see #reset()
     * @see #clear_result()
     */
    public String get_result() {
        return result.getString(ret.RESULT.name());
    }

    /**
     * Clears all unfetched errors
     * 
     * @see #get_next_error()
     * @see #has_next_error()
     */
    public void clear_errors() {
        error_list.clear();
        result.remove(ret.ERROR.name());
        clear_local_error();
    }

    /**
     * Clears all unfetched warnings
     * 
     * @see #get_next_warning()
     * @see #has_next_warning()
     */
    public void clear_warnings() {
        warning_list.clear();
    }

    /**
     * Clears the last result
     * 
     * @see #get_result()
     */
    public void clear_result() {
        result.remove(ret.RESULT.name());
    }

    /**
     * Set a callback object and method
     * 
     * <p>
     * After an async execution is finished, obj.meth() will be called. You can
     * use this in order to get notified, when encrypting/decrypting of long
     * data finishes and do not have to poll {@link #is_running()} in your
     * thread. Note, that if the call of the method fails for whatever reason,
     * you won't get notified in any way - so you still should check
     * {@link #is_running()} from time to time.
     * </p>
     * 
     * <p>
     * It produces a warning fetchable with {@link #get_next_warning()} when the
     * callback fails.
     * </p>
     * 
     * <pre>
     * <code>
     * .... your class ...
     * public void callback() {
     *   // do something after encryption finished
     * }
     * 
     * public void encrypt() {
     *   ApgCon mEnc = new ApgCon(context);
     *   // set parameters
     *   mEnc.set_arg(key, value);
     *   ...
     *   
     *   // set callback object and method 
     *   mEnc.set_callback( this, "callback" );
     *   
     *   // start asynchronous call
     *   mEnc.call_async( call );
     *   
     *   // when the call_async finishes, the method "callback()" will be called automatically
     * }
     * </code>
     * </pre>
     * 
     * @param obj
     *            The object, which has the public method meth
     * @param meth
     *            Method to call on the object obj
     */
    public void set_callback(Object obj, String meth) {
        set_callback_object(obj);
        set_callback_method(meth);
    }

    /**
     * Set a callback object
     * 
     * @param obj
     *            a object to call back after async execution
     * @see #set_callback(Object, String)
     */
    public void set_callback_object(Object obj) {
        callback_object = obj;
    }

    /**
     * Set a callback method
     * 
     * @param meth
     *            a method to call on a callback object after async execution
     * @see #set_callback(Object, String)
     */
    public void set_callback_method(String meth) {
        callback_method = meth;
    }

    /**
     * Clears any callback object
     * 
     * @see #set_callback(Object, String)
     */
    public void clear_callback_object() {
        callback_object = null;
    }

    /**
     * Clears any callback method
     * 
     * @see #set_callback(Object, String)
     */
    public void clear_callback_method() {
        callback_method = null;
    }

    /**
     * Clears any callback method and object
     * 
     * @see #set_callback(Object, String)
     */
    public void clear_callback() {
        clear_callback_object();
        clear_callback_method();
    }

    /**
     * Checks, whether an async execution is running
     * 
     * <p>
     * If you started something with {@link #call_async(String)}, this will
     * return true if the task is still running
     * </p>
     * 
     * @return true, if an async task is still running, false otherwise
     * 
     * @see #call_async(String)
     */
    public boolean is_running() {
        return async_running;
    }

    /**
     * Completely resets your instance
     * 
     * <p>
     * This currently resets everything in this instance. Errors, warnings,
     * results, callbacks, ... are removed. Any connection to the remote
     * interface is upheld, though.
     * </p>
     * 
     * <p>
     * Note, that when an async execution ({@link #call_async(String)}) is
     * running, it's result, warnings etc. will still be evaluated (which might
     * be not what you want). Also mind, that any callback you set is also
     * reseted, so on finishing the async execution any defined callback will
     * NOT BE TRIGGERED.
     * </p>
     */
    public void reset() {
        clear_errors();
        clear_warnings();
        clear_args();
        clear_callback_object();
        clear_callback_method();
        result.clear();
    }

}
