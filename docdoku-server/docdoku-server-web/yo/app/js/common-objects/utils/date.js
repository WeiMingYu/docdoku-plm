/*global _,$,define,App*/
define([
    'moment',
    'momentTimeZone'
], function (moment) {
    'use strict';

    console.log('Using timezone ' + App.config.timeZone + ' and locale ' + App.config.locale);
    // calculating the offset so that it's not calculating again by momentjs
    moment.suppressDeprecationWarnings = true;
    moment.locale(App.config.locale);

    var offset = moment().tz(App.config.timeZone).zone();
    var moffset = offset * 60 * 1000;

    return {
        formatTimestamp: function (format, timestamp) {
            if (!timestamp) {
                return '';
            }
            try {
                // set the timezone to be the current one (problem with daylight saving time)
                // and return the string with the format specified
                return moment.utc(timestamp).zone(offset).format(format);
            } catch (error) {
                console.error('Date.formatTimestamp(' + format + ', ' + timestamp + ')', error);
                return timestamp;
            }
        },

        formatLocalTime: function(format, timestamp) {
            if(!timestamp) {
                return '';
            }
            try {
                return moment(timestamp).format(format);
            } catch(error) {
                console.error('Date.formatTimestamp(' + format + ', ' + timestamp + ')', error);
                return timestamp;
            }
        },

        toUTCWithTimeZoneOffset: function (dateString) {
            // get the right timestamp from the offset calculated previously
            var dateUTCWithOffset = moment.utc(dateString).toDate().getTime() + moffset;
            return moment(dateUTCWithOffset).utc().format('YYYY-MM-DDTHH:mm:ss');
        },

        getMainZonesDates: function (timestamp) {
            var mainZones = ['America/Los_Angeles', 'America/New_York', 'Europe/London', 'Europe/Paris', 'Europe/Moscow', 'Asia/Tokyo'];
            var mainZonesDates = [];
            _(mainZones).each(function (zone) {
                mainZonesDates.push({
                    name: zone,
                    date: moment.utc(timestamp).tz(zone).format(App.config.i18n._DATE_FORMAT)
                });
            });

            mainZonesDates.push({
                name: App.config.timeZone + ' (yours)',
                date: moment.utc(timestamp).tz(App.config.timeZone).format(App.config.i18n._DATE_FORMAT)
            });

            mainZonesDates.push({
                name: 'locale',
                date: moment(timestamp).format(App.config.i18n._DATE_FORMAT)
            });
            mainZonesDates.push({
                name: 'utc',
                date: moment.utc(timestamp).format(App.config.i18n._DATE_FORMAT)
            });
            return mainZonesDates;
        },

        dateHelper: function ($querySelector) {

            $querySelector.each(function () {
                var _date = $(this).text();
                var dateUTCWithOffset = moment.utc(_date, App.config.i18n._DATE_FORMAT).toDate().getTime() + moffset;
                moment(dateUTCWithOffset).utc();

                var fromNow = moment(dateUTCWithOffset).utc().fromNow();
                $(this).popover({
                    title: '<b>' + App.config.timeZone + '</b><br /><i class="fa fa-clock-o"></i> ' + _date + '<br />' + fromNow,
                    html: true,
                    content: '<b>UTC</b><br /><i class="fa fa-clock-o"></i>  ' + moment.utc(_date, App.config.i18n._DATE_FORMAT).zone(-offset).format(App.config.i18n._DATE_FORMAT),
                    trigger: 'manual',
                    placement: 'top'
                }).click(function (e) {
                    $(this).popover('show');
                    e.stopPropagation();
                    e.preventDefault();
                    return false;
                });
            });
        }
    };
});
