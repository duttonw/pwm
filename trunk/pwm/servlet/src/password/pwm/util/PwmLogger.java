/*
 * Password Management Servlets (PWM)
 * http://code.google.com/p/pwm/
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2014 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.util;

import password.pwm.AppProperty;
import password.pwm.PwmApplication;
import password.pwm.bean.SessionLabel;
import password.pwm.error.ErrorInformation;
import password.pwm.event.AuditEvent;
import password.pwm.event.SystemAuditRecord;
import password.pwm.http.PwmRequest;
import password.pwm.http.PwmSession;
import password.pwm.util.localdb.LocalDB;
import password.pwm.util.localdb.LocalDBException;

import java.util.Date;
import java.util.HashMap;

/**
 * @author Jason D. Rivard
 */
public class PwmLogger {
// ------------------------------ FIELDS ------------------------------

    private static LocalDBLogger localDBLogger;
    private static PwmLogLevel minimumDbLogLevel;
    private static PwmApplication pwmApplication;

    private final String name;
    private final org.apache.log4j.Logger log4jLogger;
    private final boolean pwmDBdisabled;

// -------------------------- STATIC METHODS --------------------------

    public static PwmLogger getLogger(final Class className) {
        return new PwmLogger(className.getName(), false);
    }

    public static PwmLogger getLogger(final String name) {
        return new PwmLogger(name, false);
    }

    public static PwmLogger getLogger(final Class className, final boolean pwmDBdisabled) {
        return new PwmLogger(className.getName(), pwmDBdisabled);
    }

    public static PwmLogger getLogger(final String name, final boolean pwmDBdisabled) {
        return new PwmLogger(name, pwmDBdisabled);
    }


    public static LocalDBLogger initPwmApplication(
            final LocalDB pwmDB,
            final int maxEvents,
            final long maxAgeMS,
            final PwmLogLevel minimumDbLogLevel,
            final PwmApplication pwmApplication
    ) {
        final boolean devDebugMode = Boolean.parseBoolean(pwmApplication.getConfig().readAppProperty(AppProperty.LOGGING_DEV_OUTPUT));
        try {
            final LocalDBLogger.Settings settings = new LocalDBLogger.Settings();
            settings.setMaxEvents(maxEvents);
            settings.setMaxAgeMs(maxAgeMS);
            settings.setDevDebug(devDebugMode);
            PwmLogger.localDBLogger = new LocalDBLogger(pwmApplication, pwmDB, settings);
        } catch (LocalDBException e) {
            //nothing to do;
        }

        PwmLogger.minimumDbLogLevel = minimumDbLogLevel;
        PwmLogger.pwmApplication = pwmApplication;
        return PwmLogger.localDBLogger;
    }

// --------------------------- CONSTRUCTORS ---------------------------

    public PwmLogger(final String name, final boolean pwmDBdisabled) {
        this.name = name;
        this.pwmDBdisabled = pwmDBdisabled;
        log4jLogger = org.apache.log4j.Logger.getLogger(name);
    }

// --------------------- GETTER / SETTER METHODS ---------------------

    public String getName() {
        return name;
    }

// -------------------------- OTHER METHODS --------------------------

    private static String wrapWithSessionInfo(final SessionLabel sessionLabel, final String message) {
        if (sessionLabel == null) {
            return message;
        }

        final StringBuilder output = new StringBuilder();
        output.append("{");
        output.append(sessionLabel.toLabel());
        output.append("} ");
        output.append(message);

        final StringBuilder srcStr = new StringBuilder();
        final String srcAddrString = makeSrcString(sessionLabel);
        if (srcAddrString != null && srcAddrString.length() > 0) {
            srcStr.append(" [");
            srcStr.append(srcAddrString);
            srcStr.append("]");
        }

        final int firstCR = output.indexOf("\n");
        if (firstCR == -1) {
            output.append(srcStr);
        } else {
            output.insert(firstCR, srcStr);
        }

        return output.toString();
    }

    private static String makeSrcString(final SessionLabel sessionLabel) {
        try {
            final StringBuilder from = new StringBuilder();
            {
                final String srcAddress = sessionLabel.getSrcAddress();
                final String srcHostname = sessionLabel.getSrcHostname();

                if (srcAddress != null) {
                    from.append(srcAddress);
                    if (!srcAddress.equals(srcHostname)) {
                        from.append("/");
                        from.append(srcHostname);
                    }
                }
            }
            return from.toString();
        } catch (NullPointerException e) {
            return "";
        }
    }

    private static String makeActorString(final SessionLabel sessionLabel) {
        if (sessionLabel != null && sessionLabel.getUserIdentity() != null) {
            return sessionLabel.getUserIdentity().getUserDN();
        }
        return "";
    }

    private void doPwmSessionLogEvent(
            final PwmLogLevel level,
            final PwmSession pwmSession,
            final Object message,
            final Throwable e
    )
    {
        final SessionLabel sessionLabel = pwmSession != null ? pwmSession.getSessionLabel() : null;
        doLogEvent(level, sessionLabel, message, e);
    }

    private void doLogEvent(final PwmLogLevel level, final SessionLabel sessionLabel, final Object message, final Throwable e) {
        switch (level) {
            case DEBUG:
                log4jLogger.debug(wrapWithSessionInfo(sessionLabel, message.toString()), e);
                break;
            case ERROR:
                log4jLogger.error(wrapWithSessionInfo(sessionLabel, message.toString()), e);
                break;
            case INFO:
                log4jLogger.info(wrapWithSessionInfo(sessionLabel, message.toString()), e);
                break;
            case TRACE:
                log4jLogger.trace(wrapWithSessionInfo(sessionLabel, message.toString()), e);
                break;
            case WARN:
                log4jLogger.warn(wrapWithSessionInfo(sessionLabel, message.toString()), e);
                break;
            case FATAL:
                log4jLogger.fatal(wrapWithSessionInfo(sessionLabel, message.toString()), e);
                break;
        }

        try {
            final PwmLogEvent logEvent = new PwmLogEvent(
                    new Date(),
                    this.getName(),
                    message.toString(),
                    makeSrcString(sessionLabel),
                    makeActorString(sessionLabel),
                    sessionLabel != null ? sessionLabel.getSessionID() : null,
                    e,
                    level
            );

            if (localDBLogger != null && !pwmDBdisabled) {
                if (minimumDbLogLevel == null || level.compareTo(minimumDbLogLevel) >= 0) {
                    localDBLogger.writeEvent(logEvent);
                }
            }

            if (level == PwmLogLevel.FATAL) {
                if (!message.toString().contains("5039")) {
                    final HashMap<String,String> messageInfo = new HashMap<>();
                    messageInfo.put("level",logEvent.getLevel().toString());
                    messageInfo.put("actor",logEvent.getActor());
                    messageInfo.put("source",logEvent.getSource());
                    messageInfo.put("topic",logEvent.getTopic());
                    messageInfo.put("errorMessage",logEvent.getMessage());

                    final String messageInfoStr = Helper.getGson().toJson(messageInfo);
                    pwmApplication.getAuditManager().submit(SystemAuditRecord.create(
                            AuditEvent.FATAL_EVENT,
                            messageInfoStr,
                            pwmApplication.getInstanceID()
                    ));
                }
            }
        } catch (Exception e2) {
            //nothing can be done about it now;
        }
    }

    private static String convertErrorInformation(final ErrorInformation info) {
        return info.toDebugStr();
    }

    public void trace(final CharSequence message) {
        doLogEvent(PwmLogLevel.TRACE, null, message, null);
    }

    public void trace(final PwmSession pwmSession, final CharSequence message) {
        doPwmSessionLogEvent(PwmLogLevel.TRACE, pwmSession, message, null);
    }

    public void trace(final PwmRequest pwmRequest, final CharSequence message) {
        doLogEvent(PwmLogLevel.TRACE, pwmRequest.getSessionLabel(), message, null);
    }

    public void trace(final SessionLabel sessionLabel, final CharSequence message) {
        doLogEvent(PwmLogLevel.TRACE, sessionLabel, message, null);
    }

    public void trace(final CharSequence message, final Throwable exception) {
        doLogEvent(PwmLogLevel.TRACE, null, message, exception);
    }

    public void debug(final CharSequence message) {
        doLogEvent(PwmLogLevel.DEBUG, null, message, null);
    }

    public void debug(final PwmSession pwmSession, final CharSequence message) {
        doPwmSessionLogEvent(PwmLogLevel.DEBUG, pwmSession, message, null);
    }

    public void debug(final PwmSession pwmSession, final ErrorInformation errorInformation) {
        doPwmSessionLogEvent(PwmLogLevel.DEBUG, pwmSession, convertErrorInformation(errorInformation), null);
    }

    public void debug(final PwmRequest pwmRequest, final CharSequence message) {
        doLogEvent(PwmLogLevel.DEBUG, pwmRequest.getSessionLabel(), message, null);
    }

    public void debug(final PwmRequest pwmRequest, final ErrorInformation errorInformation) {
        doLogEvent(PwmLogLevel.DEBUG, pwmRequest.getSessionLabel(), convertErrorInformation(errorInformation), null);
    }

    public void debug(final SessionLabel sessionLabel, final CharSequence message) {
        doLogEvent(PwmLogLevel.DEBUG, sessionLabel, message, null);
    }

    public void debug(final SessionLabel sessionLabel, final ErrorInformation errorInformation) {
        doLogEvent(PwmLogLevel.DEBUG, sessionLabel, convertErrorInformation(errorInformation), null);
    }

    public void debug(final CharSequence message, final Throwable exception) {
        doPwmSessionLogEvent(PwmLogLevel.DEBUG, null, message, exception);
    }

    public void debug(final PwmSession pwmSession, final CharSequence message, final Throwable e) {
        doPwmSessionLogEvent(PwmLogLevel.DEBUG, pwmSession, message, e);
    }

    public void info(final CharSequence message) {
        doLogEvent(PwmLogLevel.INFO, null, message, null);
    }

    public void info(final PwmSession pwmSession, final CharSequence message) {
        doPwmSessionLogEvent(PwmLogLevel.INFO, pwmSession, message, null);
    }

    public void info(final PwmRequest pwmRequest, final CharSequence message) {
        doLogEvent(PwmLogLevel.INFO, pwmRequest.getSessionLabel(), message, null);
    }

    public void info(final SessionLabel sessionLabel, final CharSequence message) {
        doLogEvent(PwmLogLevel.INFO, sessionLabel, message, null);
    }

    public void info(final CharSequence message, final Throwable exception) {
        doLogEvent(PwmLogLevel.INFO, null, message, exception);
    }

    public void error(final CharSequence message) {
        doLogEvent(PwmLogLevel.ERROR, null, message, null);
    }

    public void error(final PwmSession pwmSession, final CharSequence message) {
        doPwmSessionLogEvent(PwmLogLevel.ERROR, pwmSession, message, null);
    }

    public void error(final PwmSession pwmSession, final ErrorInformation errorInformation) {
        doPwmSessionLogEvent(PwmLogLevel.ERROR, pwmSession, convertErrorInformation(errorInformation), null);
    }

    public void error(final PwmRequest pwmRequest, final CharSequence message) {
        doLogEvent(PwmLogLevel.ERROR, pwmRequest.getSessionLabel(), message, null);
    }

    public void error(final SessionLabel sessionLabel, final CharSequence message) {
        doLogEvent(PwmLogLevel.ERROR, sessionLabel, message, null);
    }

    public void error(final SessionLabel sessionLabel, final ErrorInformation errorInformation) {
        doLogEvent(PwmLogLevel.ERROR, sessionLabel, convertErrorInformation(errorInformation), null);
    }

    public void error(final CharSequence message, final Throwable exception) {
        doLogEvent(PwmLogLevel.ERROR, null, message, exception);
    }

    public void error(final PwmSession pwmSession, final CharSequence message, final Throwable exception) {
        doPwmSessionLogEvent(PwmLogLevel.ERROR, pwmSession, message, exception);
    }

    public void warn(final CharSequence message) {
        doLogEvent(PwmLogLevel.WARN, null, message, null);
    }

    public void warn(final PwmSession pwmSession, final CharSequence message) {
        doPwmSessionLogEvent(PwmLogLevel.WARN, pwmSession, message, null);
    }

    public void warn(final SessionLabel sessionLabel, final CharSequence message) {
        doLogEvent(PwmLogLevel.WARN, sessionLabel, message, null);
    }

    public void warn(final PwmSession pwmSession, final ErrorInformation message) {
        doPwmSessionLogEvent(PwmLogLevel.WARN, pwmSession, convertErrorInformation(message), null);
    }

    public void warn(final CharSequence message, final Throwable exception) {
        doLogEvent(PwmLogLevel.WARN, null, message, exception);
    }

    public void warn(final PwmSession pwmSession, final CharSequence message, final Throwable exception) {
        doPwmSessionLogEvent(PwmLogLevel.WARN, pwmSession, message, exception);
    }

    public void warn(final PwmSession pwmSession, final ErrorInformation errorInformation, final Throwable exception) {
        doPwmSessionLogEvent(PwmLogLevel.WARN, pwmSession, convertErrorInformation(errorInformation), exception);
    }

    public void fatal(final CharSequence message) {
        doLogEvent(PwmLogLevel.FATAL, null, message, null);
    }

    public void fatal(final PwmSession pwmSession, final CharSequence message) {
        doPwmSessionLogEvent(PwmLogLevel.FATAL, pwmSession, message, null);
    }

    public void fatal(final SessionLabel sessionLabel, final CharSequence message) {
        doLogEvent(PwmLogLevel.FATAL, sessionLabel, message, null);
    }

    public void fatal(final Object message, final Throwable exception) {
        doLogEvent(PwmLogLevel.FATAL, null, message, exception);
    }

// -------------------------- INNER CLASSES --------------------------

}

