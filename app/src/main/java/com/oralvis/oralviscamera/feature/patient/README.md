# feature/patient

Responsibilities:
- Own patient-centric behaviors at the feature layer (selection, dashboards, lookups) independent of Activities.
- Coordinate with `data` (Room) for patient entities and with identity managers for global patient context.
- Provide APIs that screens call to display or change the current patient.

Non-responsibilities:
- Defining Room entities/DAOs (belongs under `data`).
- Direct Activity or Fragment references (belongs under `ui/patient`).

