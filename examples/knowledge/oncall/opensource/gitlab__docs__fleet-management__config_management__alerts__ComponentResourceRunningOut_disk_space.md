# ComponentResourceRunningOut_disk_space

## Overview

This alert means that the disk space utilization on a disk for a node is growing rapidly and will reach it's capacity in the next 6 hours. The cause of the fast growth should be investigated.

Affected Service will be mentioned in the alert and the team owning the service can be determined in the [Service Catalog](https://gitlab.com/gitlab-com/runbooks/-/blob/master/services/service-catalog.yml) by searching for the Service name.

## Services

This alert does not have an assigned team and created from [the template](https://gitlab.com/gitlab-com/runbooks/-/blob/master/libsonnet/servicemetrics/resource_saturation_point.libsonnet#L208). So the alert can be firing for any GitLab component. To identify the team, identify the service for which the alert fired and search through the [Service Catalog](https://gitlab.com/gitlab-com/runbooks/-/blob/master/services/service-catalog.yml) to get the details about the ownership.

## Metrics

The [alert expression](https://gitlab.com/gitlab-com/runbooks/-/blob/master/libsonnet/servicemetrics/resource_saturation_point.libsonnet#L209) is predicting whether the component saturation will exceed the defined hard SLO within the specified time frame. This means that this resource is growing rapidly and is predicted to exceed saturation threshold within the specified interval.

## Alert Behavior

This alert is rare and if triggered, should be investigated, as it may lead to a service running out of available disk space, which could trigger other incidents with higher Severity. It is not recommended to silence this alert.

## Severities

This alert is usually assigned a low Severity (S4 or S3), but may become a higher severity if the resource is not investigated and the disk usage reaches the capacity.

Review [Incident Severity Handbook](https://handbook.gitlab.com/handbook/engineering/infrastructure-platforms/incident-management/#incident-severity) page to identify the required Severity Level

## Verification

Additional monitring dashboards can be found for the affected service in the "Saturation Details" section for the "disk_space component saturation" view - [HAProxy Disk Space Utilization per Device per Node Example](https://dashboards.gitlab.net/d/frontend-main/frontend3a-overview?orgId=1&viewPanel=2661375984)

## Recent changes

The alert is applicable to many services, and created from a template. To find out recent changes review the [closed prodcution issues for a specific Service](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=all&first_page_size=100). To filter the issue to the affected service from the alert apply search filter with `Label=Service::<service_name>`. [Example for HAProxy service](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=all&label_name%5B%5D=Service%3A%3AHAProxy&label_name%5B%5D=change&first_page_size=100)

## Troubleshooting

Basic approach for troubleshooting would involve finding the disk running out of space and identifying the cause of the disk usage.

- start with `sudo df -h -x squashfs` to find which disk is out of space
- use `du` and `lsof` tools to understnad what might be the reason for the disk usage
- use `sudo apt-get clean` to purge package manager cache
- consider removing old kernels with `sudo apt-get autoremove`
- examine log files for rapid growth: `find /var/log -type f -size +100M`

The goal of the troubleshooting is to understand the nature of the increased disk usage and identifying the need for adjustments, fixes or required capacity changes.

## Possible Resolutions

Examples of the previous incidents:

- [Low disk space on Gitaly storage](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/17000)
- [Disk Space Utilization for ci-runners service](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/17848)
- [All past incdents for ComponentResourceRunningOut_disk_space alert](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=closed&label_name%5B%5D=a%3AComponentResourceRunningOut_disk_space&first_page_size=100)

## Dependencies

There are no external dependencies for this alert

## Escalation

After the ownership team has been identified for the affected component, search for the Slack channel of the team and look for the escalation there.

Alternative slack channels:

- [#production_engineering](https://gitlab.enterprise.slack.com/archives/C03QC5KNW5N)
- [#infrastructure_platforms](https://gitlab.enterprise.slack.com/archives/C02D1HQRTKQ)

## Definitions

- [ComponentResourceRunningOut_ alert definition](https://gitlab.com/gitlab-com/runbooks/-/blob/master/libsonnet/servicemetrics/resource_saturation_point.libsonnet#L208)
- [Edit this playbook](ComponentResourceRunningOut_disk_space.md)
- [Update the template used to format this playbook](https://gitlab.com/gitlab-com/runbooks/-/edit/master/docs/template-alert-playbook.md)

## Related Links

- [Related alerts](./)
- [Alert template](https://gitlab.com/gitlab-com/runbooks/-/blob/master/libsonnet/servicemetrics/resource_saturation_point.libsonnet#L208)
- [Update the template used to format this playbook](https://gitlab.com/gitlab-com/runbooks/-/edit/master/docs/template-alert-playbook.md)
