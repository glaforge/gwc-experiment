// ***********************************************
// This example commands.js shows you how to
// create various custom commands and overwrite
// existing commands.
//
// For more comprehensive examples of custom
// commands please read more here:
// https://on.cypress.io/custom-commands
// ***********************************************
//
//
// -- This is a parent command --
// Cypress.Commands.add('login', (email, password) => { ... })
//
//
// -- This is a child command --
// Cypress.Commands.add('drag', { prevSubject: 'element'}, (subject, options) => { ... })
//
//
// -- This is a dual command --
// Cypress.Commands.add('dismiss', { prevSubject: 'optional'}, (subject, options) => { ... })
//
//
// -- This will overwrite an existing command --
// Cypress.Commands.overwrite('visit', (originalFn, url, options) => { ... })

// If you add a command here you also need to add the signature to ./index.ts so that typescript can verify it

import { EditorView } from '@codemirror/view'

Cypress.Commands.add('assertCodeEditorValue', (expectedValue) => {
  cy.get('#code .cm-content')
    .should((editor:any) => {
      const cm = editor[0].cmView.view as EditorView
      expect(cm.state.doc.toString()).to.eq(expectedValue)
    })
})

Cypress.Commands.add('setCodeEditorValue', (newValue) => {
  cy.get('#code .cm-content')
    .then((editor:any) => {
      const cm = editor[0].cmView.view as EditorView
      cm.dispatch({
        changes: { from: 0, to: cm.state.doc.length, insert: newValue }
      })
    })
})

Cypress.Commands.add('assertOutputEditorValue', (expectedValue) => {
  cy.get('#output .cm-content')
    .should((editor:any) => {
      const cm = editor[0].cmView.view as EditorView
      expect(cm.state.doc.toString()).to.eq(expectedValue)
    })
})

Cypress.Commands.add('assertTabActive', (tabId) => {
  cy.get(`#${tabId}`)
    .parent()
    .should('have.class', 'is-active')
})

Cypress.Commands.add('stubListRuntimes', () => {
  cy.intercept(
    {
      method: 'GET',
      url: 'https://europe-west1-gwc-experiment.cloudfunctions.net/list_runtimes'
    },
    { fixture: 'list_runtimes.json' }
  ).as('list_runtimes') // with this alias we can later refer to this mock

  // the webconsole sends a warmup request to the selected function
  cy.intercept(
    {
      method: 'GET',
      url: 'https://europe-west1-gwc-experiment.cloudfunctions.net/groovy_*'
    },
    ''
  ).as('warmup_request')
})
