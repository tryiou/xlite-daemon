package io.cloudchains.app.crypto;

import java.security.MessageDigest;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class LoginUtils {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private static String toSha256(String message) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update((message).getBytes());
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                if ((0xff & b) < 0x10)
                    hex.append("0").append(Integer.toHexString((0xFF & b)));
                else
                    hex.append(Integer.toHexString(0xFF & b));
            }
            return hex.toString();
		} catch (Exception e) {
			LOGGER.log(Level.FINER, "Error while hashing message with SHA256!");
			e.printStackTrace();
		}
		return null;
	}

	public static String loginToEntropy(String password) {
		String shaPassword = toSha256(password);

		if (shaPassword == null) {
			LOGGER.log(Level.FINER, "Password hashing failed");
			return null;
		}

		return shaPassword;
	}

}
