# LENS Architecture Design

## 1. Overview

LENS is responsible for the frontend experience and its integration with
the backend services required by the LENS application.

The architecture is organized into three independently deployable
components:

-   **LENS Frontend** --- Next.js/React application
-   **LENS API Gateway** --- external API entry point and security
    boundary
-   **LENS BFF (Backend for Frontend)** --- frontend-oriented backend
    layer that integrates with backend services

The goal is to keep the frontend decoupled from the internal structure
of the backend services while giving the LENS team clear ownership of
the frontend integration layer.

## 2. High-Level Architecture

``` text
                         Browser
                            |
                            v
                 +----------------------+
                 |   lens-frontend      |
                 |   Next.js / React    |
                 +----------+-----------+
                            |
                            v
                 +----------------------+
                 |   lens-gateway       |
                 |                      |
                 |   Routing            |
                 |   Authentication     |
                 |   Authorization      |
                 +----------+-----------+
                            |
                            v
                 +----------------------+
                 |      lens-bff        |
                 |                      |
                 |   Controllers        |
                 |   Aggregation         |
                 |   DTOs               |
                 +-----+-------+--------+
                       |       |
                 +-----+-------+-----+
                 |           |       |
                 v           v       v
              Inbox       Chain    Mind
```

## 3. LENS Frontend

**Technology:** Next.js / React

The frontend is responsible for the user-facing application experience,
including:

-   Application shell and navigation
-   Pages and routing
-   Shared UI components
-   Rendering data received from the LENS BFF
-   Frontend authentication state and protected UI flows

The frontend should communicate through the LENS API boundary rather
than depend directly on the internal APIs of Inbox, Chain, or Mind.

## 4. LENS API Gateway

The API Gateway is the external entry point for the LENS backend.

### Responsibilities

-   API routing
-   Authentication
-   Token/session validation
-   Authorization
-   Common API-level error handling
-   Acting as the security boundary between the frontend and backend

Authentication and authorization are part of the Gateway responsibility.
They are not defined as a separate deployable service in this
architecture.

The Gateway should remain focused on edge/API concerns and should not
contain domain logic belonging to Inbox, Chain, or Mind.

## 5. LENS BFF

The LENS BFF is a backend layer specifically designed for the needs of
the LENS frontend.

### Responsibilities

-   Expose frontend-oriented APIs
-   Provide controllers for LENS endpoints
-   Aggregate information from backend services
-   Orchestrate calls to multiple services when required by a frontend
    use case
-   Define and expose frontend-facing DTOs
-   Keep the frontend independent of the internal service decomposition

The BFF may communicate with:

-   **Inbox** --- task/work-related information
-   **Chain** --- dependency/blocker information
-   **Mind** --- intelligence and insight information

The BFF is an integration layer; domain ownership remains with the
respective backend services.

## 6. Request Flow

A typical request follows this flow:

``` text
Browser
   |
   v
LENS Frontend
   |
   v
API Gateway
   |
   | Authentication / Authorization
   |
   v
LENS BFF
   |
   +----> Inbox
   |
   +----> Chain
   |
   +----> Mind
   |
   v
Aggregated FE response
   |
   v
LENS Frontend
```

The frontend therefore does not need to know which backend service owns
each piece of information.

## 7. Authentication and Identity

Authentication and authorization are handled at the Gateway boundary.

The Auth & Identity contract defines:

-   Authentication flow (for example OAuth, OTP, or Mock SSO)
-   User identity model
-   Roles and permissions
-   Token/session handling
-   Propagation of the authenticated user context from Gateway to BFF
    and downstream services

The initial role model includes the LENS use cases for **manager** and
**employee**.

## 8. Deployment Model

Each of the three main LENS components is independently deployable and
can run in its own Docker container:

``` text
lens-frontend container
        |
        v
lens-gateway container
        |
        v
lens-bff container
        |
        +----> Inbox
        +----> Chain
        +----> Mind
```

For local development, the components can run on a shared Docker network
and communicate using service names.

This keeps deployment, restart, logging, health checks, and ownership
boundaries independent while allowing the components to operate as one
application.

## 9. Ownership Boundaries

  -----------------------------------------------------------------------
  Component                           Primary Responsibility
  ----------------------------------- -----------------------------------
  **LENS Frontend**                   User experience, pages, routing, UI

  **LENS Gateway**                    API entry point, authentication,
                                      authorization, routing

  **LENS BFF**                        FE APIs, aggregation,
                                      orchestration, DTOs

  **Inbox**                           Owns its domain data and business
                                      logic

  **Chain**                           Owns its domain data and business
                                      logic

  **Mind**                            Owns its domain data and business
                                      logic
  -----------------------------------------------------------------------

LENS components should not duplicate domain logic owned by the
downstream services.

## 10. Team Structure

The architecture supports four clear areas of ownership within the LENS
team:

1.  **Frontend** --- Next.js/React and frontend foundation
2.  **API Gateway** --- routing and API edge concerns
3.  **Auth & Identity** --- authentication, authorization, roles, and
    user context
4.  **LENS BFF** --- frontend APIs, aggregation, orchestration, and
    downstream integration

Auth & Identity is implemented within the Gateway boundary rather than
as a separate deployable service.

## 11. Design Principles

-   Keep the frontend independent of backend service decomposition.
-   Keep authentication and authorization at the API boundary.
-   Keep the BFF focused on frontend integration rather than domain
    ownership.
-   Keep domain logic in the service that owns the relevant domain.
-   Keep Gateway and BFF independently deployable.
-   Define clear contracts between Gateway, BFF, and downstream
    services.
