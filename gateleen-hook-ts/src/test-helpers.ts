export function createOkResponse(): Response {
  return new Response('{}', {
    status: 200,
    headers: {
      'Content-Type': 'application/json',
    },
  });
}
