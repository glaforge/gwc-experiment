import { Observable } from 'rxjs'
import { fromFetch } from 'rxjs/fetch'
import { concatMap, map } from 'rxjs/operators'
import { StackOverflowResponse } from './types'

const baseUrl = 'https://api.stackexchange.com/'
const codeRegex = /```(?:groovy)?([^]+?)```/gm

export function loadCodeFromQuestion (questionId: string): Observable<string> {
  const headers = new Headers()
  headers.set('Accept', 'application/json')
  return fromFetch(`${baseUrl}2.2/questions/${questionId}?order=desc&sort=activity&site=stackoverflow&filter=!nL_HTx9V7w`, { headers })
    .pipe(
      concatMap(response => response.json()),
      map((json: StackOverflowResponse) => json.items[0].body_markdown),
      map(body => {
        const results = []
        let result
        while ((result = codeRegex.exec(body))) {
          const match = result[1]
            .replace(/&quot;/g, '"')
            .replace(/&gt;/g, '>')
            .replace(/&lt;/g, '<')
          results.push(match)
        }
        if (results.length === 0) {
          throw new Error('Could not find any groovy code in the question.\nThe code was probably not properly formatted.\nOnly multi-line code is extracted.')
        }
        return results.join('\n\n')
      })
    )
}
