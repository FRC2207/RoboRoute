
import { Children, useState } from "react";
import CodeBlock from "@theme/CodeBlock";
import styles from "./styles.module.css";

type Field = {
    name: string;
    type: string;
    required?: boolean;
    description: string;
};

type Step = {
    label: string;
    description: string;
    fields: Field[];
    example: Record<string, unknown>;
};

const steps: Record<string, Step> = {
    PATH: {
        label: "Path",
        description: "Follows a predefined PathPlanner path.",
        fields: [
            {
                name: "type",
                type: '"PATH"',
                required: true,
                description: "Identifies this step as a path step.",
            },
            {
                name: "path",
                type: "string",
                required: true,
                description: "The name of the path to execute.",
            },
        ],
        example: {
            type: "PATH",
            path: "ExamplePath",
        },
    },

    POSE: {
        label: "Pose",
        description: "Moves the robot toward a target pose.",
        fields: [
            {
                name: "type",
                type: '"POSE"',
                required: true,
                description: "Identifies this step as a pose step.",
            },
            {
                name: "pose",
                type: "object",
                required: true,
                description: "The target position and rotation.",
            },
        ],
        example: {
            type: "POSE",
            pose: {
                x: 2.5,
                y: 1.2,
                rotation: 90,
            },
        },
    },

    ROUTE: {
        label: "Route",
        description: "Moves the robot smoothly along a set of poses.",
        fields: [
            {
                name: "type",
                type: '"ROUTE"',
                required: true,
                description: "Identifies this step as a route.",
            },
            {
                name: "routePoses",
                type: "object[]",
                required: true,
                description: "A list of poses that are waypoints on a path.",
            },
        ],
        example: {
            type: "ROUTE",
            routePoses: [
                {
                    x: 2.5,
                    y: 1.2,
                    rotation: 90,
                },
                {
                    x: 3.5,
                    y: 2.2,
                    rotation: 85,
                },
            ],
        },
    },

    ACTION: {
        label: "Action",
        description: "Executes a predefined custom action on the robot.",
        fields: [
            {
                name: "type",
                type: '"ACTION"',
                required: true,
                description: "Identifies this step as a action step.",
            },
            {
                name: "actionId",
                type: "int",
                required: true,
                description: "The numerical id of the action to execute.",
            },
        ],
        example: {
            type: "action",
            actionId: 1,
        },
    },

    SEQUENCE: {
        label: "Sequence",
        description: "A list of steps that run in order.",
        fields: [
            {
                name: "type",
                type: '"SEQUENCE"',
                required: true,
                description: "Identifies this step as a sequence.",
            },
            {
                name: "children",
                type: "step[]",
                required: true,
                description: "A list of steps that will be run sequentially.",
            },
        ],
        example: {
            type: "SEQUENCE",
            children: [
                {
                    type: "POSE",
                    pose: {
                        x: 2.5,
                        y: 1.2,
                        rotation: 90,
                    },
                },
                {
                    type: "ROUTE",
                    routePoses: [
                        {
                            x: 2.5,
                            y: 1.2,
                            rotation: 90,
                        },
                        {
                            x: 3.5,
                            y: 2.2,
                            rotation: 85,
                        },
                    ],
                },
            ]
        }
    },

    PARALLEL: {
        label: "Parallel",
        description: "A list of steps that run at the same time.",
        fields: [
            {
                name: "type",
                type: '"PARALLEL"',
                required: true,
                description: "Identifies this step as a parallel group.",
            },
            {
                name: "children",
                type: "step[]",
                required: true,
                description: "A list of steps that will be run at the same time.",
            },
        ],
        example: {
            type: "PARALLEL",
            children: [
                {
                    type: "POSE",
                    pose: {
                        x: 2.5,
                        y: 1.2,
                        rotation: 90,
                    },
                },
                {
                    type: "action",
                    actionId: 1,
                },
            ]
        }
    }
};

export default function StepDocs() {
    const [selected, setSelected] = useState<string>("PATH");
    const step = steps[selected];

    return (
        <div className={styles.container}>
            <nav className={styles.sidebar} aria-label="Step types">
                <div className={styles.sidebarTitle}>Step types</div>

                {Object.entries(steps).map(([key, value]) => (
                    <button
                        key={key}
                        type="button"
                        className={`${styles.option} ${selected === key ? styles.active : ""
                            }`}
                        onClick={() => setSelected(key)}
                        aria-pressed={selected === key}
                    >
                        <span className={styles.optionName}>{value.label}</span>
                    </button>
                ))}
            </nav>

            <section className={styles.content}>
                <div className={styles.heading}>
                    <div>
                        <h3>{step.label}</h3>
                        <p>{step.description}</p>
                    </div>
                </div>

                <h3>Fields</h3>

                <div className={styles.fields}>
                    {step.fields.map((field) => (
                        <div className={styles.field} key={field.name}>
                            <div className={styles.fieldTop}>
                                <code>{field.name}</code>
                                <code className={styles.type}>{field.type}</code>
                            </div>

                            <p>{field.description}</p>

                            {field.required && (
                                <span className={styles.required}>Required</span>
                            )}
                        </div>
                    ))}
                </div>
            </section>

            <aside className={styles.example}>
                <div className={styles.exampleHeading}>
                    <div>
                        <h3>Example</h3>
                    </div>
                </div>

                <CodeBlock language="json">
                    {JSON.stringify(step.example, null, 2)}
                </CodeBlock>
            </aside>
        </div>
    );
}