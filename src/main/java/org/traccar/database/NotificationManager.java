/*
 * Copyright 2016 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2018 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.database;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.Context;
import org.traccar.config.Keys;
import org.traccar.geocoder.Geocoder;
import org.traccar.model.Calendar;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Position;
import org.traccar.model.Typed;
import org.traccar.model.User;
import org.traccar.notification.EventForwarder;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificatorManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;

import javax.annotation.Nullable;

public class NotificationManager extends ExtendedObjectManager<Notification> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationManager.class);

    private final CacheManager cacheManager;
    private final EventForwarder eventForwarder;
    private final NotificatorManager notificatorManager;
    private final Geocoder geocoder;

    private final boolean geocodeOnRequest;

    public NotificationManager(
            DataManager dataManager, CacheManager cacheManager, @Nullable EventForwarder eventForwarder,
            NotificatorManager notificatorManager, @Nullable Geocoder geocoder) {
        super(dataManager, Notification.class);
        this.cacheManager = cacheManager;
        this.eventForwarder = eventForwarder;
        this.notificatorManager = notificatorManager;
        this.geocoder = geocoder;
        geocodeOnRequest = Context.getConfig().getBoolean(Keys.GEOCODER_ON_REQUEST);
    }

    private Set<Long> getEffectiveNotifications(long userId, long deviceId, Date time) {
        Set<Long> result = new HashSet<>();
        Set<Long> deviceNotifications = getAllDeviceItems(deviceId);
        for (long itemId : getUserItems(userId)) {
            if (getById(itemId).getAlways() || deviceNotifications.contains(itemId)) {
                long calendarId = getById(itemId).getCalendarId();
                Calendar calendar = calendarId != 0 ? cacheManager.getObject(Calendar.class, calendarId) : null;
                if (calendar == null || calendar.checkMoment(time)) {
                    result.add(itemId);
                }
            }
        }
        return result;
    }

    public void updateEvent(Event event, Position position) {
        try {
            getDataManager().addObject(event);
        } catch (StorageException error) {
            LOGGER.warn("Event save error", error);
        }

        long deviceId = event.getDeviceId();
        Set<Long> users = Context.getPermissionsManager().getDeviceUsers(deviceId);
        Set<Long> usersToForward = new HashSet<>();
        for (long userId : users) {
            usersToForward.add(userId);
            final Set<String> notificators = new HashSet<>();
            for (long notificationId : getEffectiveNotifications(userId, deviceId, event.getEventTime())) {
                Notification notification = getById(notificationId);
                if (getById(notificationId).getType().equals(event.getType())) {
                    boolean filter = false;
                    if (event.getType().equals(Event.TYPE_ALARM)) {
                        String alarmsAttribute = notification.getString("alarms");
                        if (alarmsAttribute == null) {
                            filter = true;
                        } else {
                            List<String> alarms = Arrays.asList(alarmsAttribute.split(","));
                            filter = !alarms.contains(event.getString(Position.KEY_ALARM));
                        }
                    }
                    if (!filter) {
                        notificators.addAll(notification.getNotificatorsTypes());
                    }
                }
            }

            if (position != null && position.getAddress() == null && geocodeOnRequest && geocoder != null) {
                position.setAddress(geocoder.getAddress(position.getLatitude(), position.getLongitude(), null));
            }

            User user = Context.getUsersManager().getById(userId);
            new Thread(() -> {
                for (String notificator : notificators) {
                    try {
                        notificatorManager.getNotificator(notificator).send(user, event, position);
                    } catch (MessageException | InterruptedException exception) {
                        LOGGER.warn("Notification failed", exception);
                    }
                }
            }).start();
        }
        if (eventForwarder != null) {
            eventForwarder.forwardEvent(event, position, usersToForward);
        }
    }

    public void updateEvents(Map<Event, Position> events) {
        for (Entry<Event, Position> event : events.entrySet()) {
            updateEvent(event.getKey(), event.getValue());
        }
    }

    public Set<Typed> getAllNotificationTypes() {
        Set<Typed> types = new HashSet<>();
        Field[] fields = Event.class.getDeclaredFields();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("TYPE_")) {
                try {
                    types.add(new Typed(field.get(null).toString()));
                } catch (IllegalArgumentException | IllegalAccessException error) {
                    LOGGER.warn("Get event types error", error);
                }
            }
        }
        return types;
    }
}
